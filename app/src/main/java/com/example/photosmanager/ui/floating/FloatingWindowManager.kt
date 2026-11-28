package com.example.photosmanager.ui.floating

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.photosmanager.data.model.PendingMove
import com.example.photosmanager.ui.confirm.ConfirmDeleteActivity
import com.example.photosmanager.util.PermissionHelper

class FloatingWindowManager(private val context: Context) {
    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show() {
        try {
            if (!PermissionHelper.hasOverlayPermission(context)) {
                Log.e("FloatingWindowManager", "没有悬浮窗权限")
                return
            }
            if (composeView != null) {
                Log.d("FloatingWindowManager", "悬浮窗已在显示，保持当前进度")
                return
            }

            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager == null) {
                Log.e("FloatingWindowManager", "无法获取 WindowManager")
                return
            }

            val appContext = if (context is android.app.Service) {
                context.applicationContext
            } else {
                context
            }

            val owner = object : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
                private val lifecycleRegistry = LifecycleRegistry(this)
                private val savedStateRegistryController = SavedStateRegistryController.create(this)
                private val _viewModelStore = ViewModelStore()

                init {
                    try {
                        savedStateRegistryController.performRestore(android.os.Bundle())
                    } catch (e: Exception) {
                        Log.e("FloatingWindowManager", "performRestore 在 init 中失败", e)
                    }
                    lifecycleRegistry.currentState = Lifecycle.State.CREATED
                }

                override val lifecycle: Lifecycle
                    get() = lifecycleRegistry

                override val savedStateRegistry: SavedStateRegistry
                    get() = savedStateRegistryController.savedStateRegistry

                override val viewModelStore: ViewModelStore
                    get() = _viewModelStore
            }

            lifecycleOwner = owner

            composeView = ComposeView(appContext).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
            }

            composeView?.setContent {
                com.example.photosmanager.ui.theme.PHOTOSMANAGERTheme {
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurface
                    ) {
                        FloatingWindowContent(
                            onDismiss = { dismiss() },
                            onFocusableChange = { focusable -> setFocusable(focusable) },
                            onComplete = { photosToDelete, photosToMove ->
                                dismiss()
                                if (photosToDelete.isNotEmpty() || photosToMove.isNotEmpty()) {
                                    showConfirmDeleteActivity(photosToDelete, photosToMove)
                                }
                            }
                        )
                    }
                }
            }

            (lifecycleOwner?.lifecycle as? LifecycleRegistry)?.currentState = Lifecycle.State.STARTED
            (lifecycleOwner?.lifecycle as? LifecycleRegistry)?.currentState = Lifecycle.State.RESUMED

            val displayMetrics = android.content.res.Resources.getSystem().displayMetrics
            val windowHeight = (displayMetrics.heightPixels * 0.75).toInt()

            val params = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = windowHeight
                gravity = Gravity.BOTTOM
                x = 0
                y = 0
            }
            layoutParams = params

            windowManager?.addView(composeView, params)
        } catch (e: Exception) {
            Log.e("FloatingWindowManager", "显示悬浮窗失败", e)
        }
    }

    fun setFocusable(focusable: Boolean) {
        val view = composeView ?: return
        val params = layoutParams ?: return
        val wm = windowManager ?: return
        try {
            val newFlags = if (focusable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            val newSoftInputMode = if (focusable) {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
            }
            if (params.flags != newFlags || params.softInputMode != newSoftInputMode) {
                params.flags = newFlags
                params.softInputMode = newSoftInputMode
                wm.updateViewLayout(view, params)
            }
        } catch (e: Exception) {
            Log.w("FloatingWindowManager", "更新悬浮窗焦点标志失败", e)
        }
    }

    fun dismiss() {
        try {
            lifecycleOwner?.let { owner ->
                val lifecycle = owner.lifecycle
                if (lifecycle is LifecycleRegistry) {
                    lifecycle.currentState = Lifecycle.State.DESTROYED
                }
                if (owner is ViewModelStoreOwner) {
                    owner.viewModelStore.clear()
                }
            }
            composeView?.let { view ->
                windowManager?.removeView(view)
            }
        } catch (e: Exception) {
            Log.e("FloatingWindowManager", "关闭悬浮窗失败", e)
        } finally {
            composeView = null
            windowManager = null
            lifecycleOwner = null
            layoutParams = null
        }
    }

    private fun showConfirmDeleteActivity(
        photosToDelete: List<String>,
        photosToMove: List<PendingMove>
    ) {
        val intent = Intent(context, ConfirmDeleteActivity::class.java).apply {
            putStringArrayListExtra(ConfirmDeleteActivity.EXTRA_PHOTOS_TO_DELETE, ArrayList(photosToDelete))
            if (photosToMove.isNotEmpty()) {
                putExtra(ConfirmDeleteActivity.EXTRA_PHOTOS_TO_MOVE_JSON, PendingMove.toJson(photosToMove))
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        context.startActivity(intent)
    }
}
