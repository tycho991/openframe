package io.github.tycho991.openframe.android

import android.view.Window
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * [inner] with every touch reported to [onTouch] before it is handled. A dialog has a window of its own, so its
 * touches never pass `DisplayActivity.dispatchTouchEvent`; without this, a frame woken at night would switch off while
 * someone uses the device menu. A proxy, because `Window.Callback` gains methods with new Android versions.
 */
internal fun reportingTouches(
    inner: Window.Callback,
    onTouch: () -> Unit,
): Window.Callback =
    Proxy.newProxyInstance(Window.Callback::class.java.classLoader, arrayOf(Window.Callback::class.java)) { _, method, args ->
        if (method.name == "dispatchTouchEvent") onTouch()
        try {
            method.invoke(inner, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    } as Window.Callback
