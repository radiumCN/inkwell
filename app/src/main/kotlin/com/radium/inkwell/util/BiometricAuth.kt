package com.radium.inkwell.util

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 系统级身份验证（指纹 / 面容 / 设备密码）。
 *
 * 一律带上 DEVICE_CREDENTIAL 回退：只认指纹的话，没录指纹、或指纹传感器坏了的人，
 * 就被永久锁在自己的书外面 —— 而这是个**没有找回途径**的锁（书在本地，我们不做账号）。
 */
object BiometricAuth {

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** 这台设备能不能验（没录指纹也没设锁屏密码的话就是不能） */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    sealed interface Result {
        data object Success : Result
        /** 用户主动取消，不用报错打扰他 */
        data object Cancelled : Result
        data class Failed(val message: String) : Result
    }

    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
    ): Result = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(Result.Success)
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (!cont.isActive) return
                    val cancelled = code == BiometricPrompt.ERROR_USER_CANCELED ||
                        code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        code == BiometricPrompt.ERROR_CANCELED
                    cont.resume(
                        if (cancelled) Result.Cancelled else Result.Failed(message.toString()),
                    )
                }
                // onAuthenticationFailed（指纹没对上）不结束流程 —— 系统会让用户再试
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { subtitle?.let { setSubtitle(it) } }
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }
}
