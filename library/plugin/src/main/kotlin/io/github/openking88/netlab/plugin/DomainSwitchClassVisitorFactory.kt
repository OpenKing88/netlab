package io.github.openking88.netlab.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor

/**
 * AGP 的插桩入口。
 *
 * 作用范围由 [InstrumentationScope.PROJECT] 限定为"宿主自己的类"，
 * 因此三方 SDK（Firebase / Adjust / 各种统计 SDK）内部创建的 OkHttpClient 不会被改写。
 */
abstract class DomainSwitchClassVisitorFactory :
    AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun isInstrumentable(classData: ClassData): Boolean {
        // 再排除本库自身，避免自我插桩
        return !classData.className.startsWith("io.github.openking88.netlab")
    }

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        return DomainSwitchClassVisitor(
            nextClassVisitor = nextClassVisitor,
            currentClassName = classContext.currentClassData.className
        )
    }
}
