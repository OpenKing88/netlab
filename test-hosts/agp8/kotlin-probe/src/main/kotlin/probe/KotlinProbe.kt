package probe

/**
 * 兼容性探针：本模块用 Kotlin 2.0.21（Gradle 8.13 内嵌版本）编译、stdlib 走 compileOnly。
 *
 * 对比实验的另一半：上一个用 2.3.20 编译的探针，让「宿主源码完全不引用它」的该项目
 * 编译失败了。这里验证「对齐宿主最老版本编译」是否可行。
 */
object KotlinProbe {

    fun greeting(): String = listOf("domain", "switch").joinToString(separator = "-")
}
