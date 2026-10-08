package io.github.netlab.plugin

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * 在宿主自己创建的 OkHttpClient 上挂载域名拦截器。
 *
 * 需要三个插桩点，缺一个都会漏：
 *
 * 1. `new OkHttpClient.Builder()` → 注入 `install(builder)`，**拷贝一份** Builder 引用交给 install，
 *    原始的留给后续字节码。注入发生在宿主 `addInterceptor(...)` 之前，拦截器天然排在第 0 位。
 *
 * 2. `new OkHttpClient()` → 注入 `installClient(client)`，**替换**刚创建出来的实例。
 *    必须单独处理，因为无参构造内部的 `new Builder()` 发生在 okhttp 自己的类里，
 *    宿主类看不到；只靠第 1 点会漏掉 `OkHttpClient().newBuilder()...` 这种最常见的写法。
 *
 * 3. `builder.build()` → 注入 `installClient(client)`，兜底。
 *    只要 build() 是在宿主类里调用的就一定能兜住，且 `installClient` 幂等，不会重复添加。
 *
 * `OkHttpClient.Builder(existing)` 拷贝构造刻意不匹配，`newBuilder()` 因此不会重复注入。
 */
internal class DomainSwitchClassVisitor(
    private val currentClassName: String,
    nextClassVisitor: ClassVisitor
) : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor? {
        val methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions)
            ?: return null
        return object : MethodVisitor(Opcodes.ASM9, methodVisitor) {
            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
                isInterface: Boolean
            ) {
                // WebView 的加载调用是"整体替换"：把 INVOKEVIRTUAL 换成同签名的 INVOKESTATIC，
                // 操作数栈形状完全一致（receiver + 参数），所以不需要任何栈操作。
                if (opcode == Opcodes.INVOKEVIRTUAL && owner == WEB_VIEW_OWNER) {
                    val staticDescriptor = webViewStaticDescriptor(name = name, descriptor = descriptor)
                    if (staticDescriptor != null) {
                        visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            AGENT_OWNER,
                            name,
                            staticDescriptor,
                            false
                        )
                        return
                    }
                }

                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                when {
                    // ① new OkHttpClient.Builder()
                    opcode == Opcodes.INVOKESPECIAL &&
                            owner == OK_HTTP_BUILDER_OWNER &&
                            name == CONSTRUCTOR &&
                            descriptor == NO_ARG_CONSTRUCTOR -> {
                        // 栈顶还留着刚构造出来的 Builder：复制一份给 install
                        visitInsn(Opcodes.DUP)
                        visitAgentCall(
                            methodName = "install",
                            methodDescriptor = INSTALL_BUILDER_DESCRIPTOR
                        )
                    }

                    // ② new OkHttpClient()
                    // 栈上此时正好是 new 出来的实例，installClient 的返回值直接顶替它
                    opcode == Opcodes.INVOKESPECIAL &&
                            owner == OK_HTTP_CLIENT_OWNER &&
                            name == CONSTRUCTOR &&
                            descriptor == NO_ARG_CONSTRUCTOR -> {
                        visitAgentCall(
                            methodName = "installClient",
                            methodDescriptor = INSTALL_CLIENT_DESCRIPTOR
                        )
                    }

                    // ③ builder.build()
                    // 栈上此时正好是 build() 的返回值，同样直接顶替
                    opcode == Opcodes.INVOKEVIRTUAL &&
                            owner == OK_HTTP_BUILDER_OWNER &&
                            name == "build" &&
                            descriptor == BUILD_DESCRIPTOR -> {
                        visitAgentCall(
                            methodName = "installClient",
                            methodDescriptor = INSTALL_CLIENT_DESCRIPTOR
                        )
                    }
                }
            }
        }
    }

    private fun MethodVisitor.visitAgentCall(methodName: String, methodDescriptor: String) {
        visitMethodInsn(
            Opcodes.INVOKESTATIC,
            AGENT_OWNER,
            methodName,
            methodDescriptor,
            false
        )
    }

    /**
     * 命中 WebView 的加载入口时，返回替换后的静态方法描述符（在原描述符前面加上 WebView 自身）。
     */
    private fun webViewStaticDescriptor(name: String, descriptor: String): String? {
        val isWebViewLoadingCall = when {
            name == "loadUrl" ->
                descriptor == LOAD_URL_DESCRIPTOR || descriptor == LOAD_URL_WITH_HEADERS_DESCRIPTOR

            name == "loadDataWithBaseURL" -> descriptor == LOAD_DATA_DESCRIPTOR

            else -> false
        }
        if (!isWebViewLoadingCall) {
            return null
        }
        // 描述符形如 "(Ljava/lang/String;)V"：
        // 去掉开头的 '('，把 WebView 自身作为第一个参数插进去
        return "(Landroid/webkit/WebView;" + descriptor.substring(1)
    }

    private companion object {
        const val OK_HTTP_BUILDER_OWNER = "okhttp3/OkHttpClient\$Builder"
        const val OK_HTTP_CLIENT_OWNER = "okhttp3/OkHttpClient"
        const val WEB_VIEW_OWNER = "android/webkit/WebView"
        const val CONSTRUCTOR = "<init>"
        const val NO_ARG_CONSTRUCTOR = "()V"
        const val BUILD_DESCRIPTOR = "()Lokhttp3/OkHttpClient;"
        const val LOAD_URL_DESCRIPTOR = "(Ljava/lang/String;)V"
        const val LOAD_URL_WITH_HEADERS_DESCRIPTOR = "(Ljava/lang/String;Ljava/util/Map;)V"
        const val LOAD_DATA_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
        const val AGENT_OWNER = "io/github/netlab/DomainSwitchAgent"
        const val INSTALL_BUILDER_DESCRIPTOR = "(Lokhttp3/OkHttpClient\$Builder;)V"
        const val INSTALL_CLIENT_DESCRIPTOR = "(Lokhttp3/OkHttpClient;)Lokhttp3/OkHttpClient;"
    }
}
