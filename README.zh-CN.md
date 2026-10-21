# Kotlin ObjC Export Kit（KOEK）

[English](README.md)

**明确选择 iOS 能使用的 Kotlin 接口，按业务组织头文件，最终仍交付一个 Framework。**

KOEK 是一套 Kotlin/Native Objective-C 导出工具。通过 `@ObjCExport`
标记类型和成员，并指定头文件分组。没有选择的声明仍可被 Kotlin 代码使用，
但不会自动成为 iOS 的公开入口。

目前是面向 **Kotlin 2.2.21** 的**实验性源码项目**，依赖编译器内部 API 和反射阶段拦截。
尚未发布到 Gradle Plugin Portal 或 Maven Central。当前独立仓库尚未完成编译、链接及运行
验证矩阵；文中的命令是复现步骤，不代表已经验证通过。

## 它解决什么问题

Kotlin/Native 默认可能将许多 iOS 用不到的公开声明放入同一个大头文件。
Kotlin 中的 `public` 往往只是共享代码的访问需求，并不意味着每个方法都应成为 iOS API。

KOEK 使用“默认不开放，明确标记才开放”的白名单。例如：

```kotlin
import io.github.sugite.koek.annotations.ObjCExport
import io.github.sugite.koek.annotations.ObjCExportAccess

@ObjCExport(group = "Services")
class GreetingService @ObjCExport constructor() {
    @ObjCExport(access = ObjCExportAccess.READWRITE)
    var enabled: Boolean = true

    @ObjCExport
    fun greet(name: String): String = if (enabled) "Hello, $name!" else ""

    fun diagnosticMessage(): String = "Kotlin-only"
}
```

iOS 可以创建对象、读写 `enabled`、调用 `greet`，但不能直接调用未选择的
`diagnosticMessage`。**标记类不会顺带公开其构造方法和全部成员。**

最终仍是一个代码包，Swift 继续使用 `import DemoKit`：

```text
DemoKit.framework/
├── DemoKit
├── Headers/
│   ├── DemoKit.h
│   ├── DemoKitBase.h
│   ├── DemoKitModels.h
│   └── DemoKitServices.h
└── Modules/
    ├── module.modulemap
    └── objc-export-headers.txt
```

分组文件名使用“编译器的 Objective-C 顶层名称前缀 + 分组名”。这里假设采用
`DemoKit` 的默认前缀；主头文件负责汇总各组。

## 环境与支持范围

| 项目 | 当前范围 |
| --- | --- |
| Kotlin / Kotlin Gradle plugin | 严格限定 **2.2.21**，其他编译器版本会被拒绝 |
| 插件构建 JDK | **21** |
| Gradle Wrapper | **9.3.1**，不承诺其他版本兼容性 |
| Native 构建环境 | macOS、已安装并选中的 Xcode 及对应 Apple SDK |
| 注解库目标 | JVM 17、iOS x64、iOS arm64、iOS simulator arm64 |
| 导出后端 | Kotlin/Native 的 **Objective-C export**，不适用于 Swift export |
| 接入方式 | 本地 composite build，尚无公开发布的二进制版本 |
| 许可证 | KOEK 使用 MIT，第三方文件见 [声明](THIRD_PARTY_NOTICES.md) |

注解库没有平台专用实现。Gradle 插件只接入 Kotlin/Native 的 **Framework 链接任务**，
不改写 Android/JVM 编译任务。其他 Native 平台需要自行补充对应注解目标并验证，
本仓库没有启用这些目标。

## 运行通用示例

[examples/basic](examples/basic) 是独立的消费工程，包含 DTO、回调协议、服务和部分枚举导出。
选择 JDK 21 后，在仓库根目录执行：

```bash
./gradlew -p examples/basic :shared:linkDebugFrameworkIosSimulatorArm64
```

Framework 输出位置：

```text
examples/basic/shared/build/bin/iosSimulatorArm64/debugFramework/DemoKit.framework
```

真机任务为 `:shared:linkDebugFrameworkIosArm64`。示例不安装或运行 iOS App，也不发布产物。

## 接入已有项目

将本仓库放在消费项目旁边。以下配置假设相对路径为 `../KotlinObjcExportKit`。
在消费项目的 `settings.gradle.kts` 中合并以下内容：

```kotlin
pluginManagement {
    includeBuild("../KotlinObjcExportKit")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

includeBuild("../KotlinObjcExportKit") {
    dependencySubstitution {
        substitute(module("io.github.sugite.koek:compiler-plugin"))
            .using(project(":compiler-plugin"))
        substitute(module("io.github.sugite.koek:annotations"))
            .using(project(":annotations"))
    }
}
```

两处 `includeBuild` 分别服务于插件解析和普通依赖替换，均需保留。
请合并到已有 settings 配置，不要重复声明同一个配置块。

在产出 Framework 的模块中启用插件：

```kotlin
plugins {
    kotlin("multiplatform") version "2.2.21"
    id("io.github.sugite.koek")
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "DemoKit"
        }
    }
    sourceSets.commonMain.dependencies {
        implementation("io.github.sugite.koek:annotations:0.1.0")
    }
}
```

每个使用注解的源码模块都需要直接依赖注解库。若 Framework 汇总其他模块，保留对应的
`api(project(...))` 和 `export(project(...))`。插件扫描当前模块及明确导出的模块，
不会因为某个普通传递依赖带有注解，就自动把整个依赖开放给 iOS。

## 注解规则

| 声明 | 如何选择 |
| --- | --- |
| 类、接口 | 标记 `@ObjCExport(group = "Models")`；接口映射为 Objective-C protocol |
| 构造方法 | 单独标记构造方法 |
| 方法 | 标记需要的声明；重载不会一起开放 |
| 只读属性 | 标记属性即可；即使 Kotlin 中是 var，默认也只读 |
| 可写属性 | 设置 `access = ObjCExportAccess.READWRITE`，且 setter 必须可导出 |
| 构造参数属性 | 在 val／var 上使用 `@property:ObjCExport` |
| 嵌套类型 | 单独标记，外层类型的标记不会自动选择它 |
| 顶层声明 | 自己指定 group |

成员可以继承包围类型的分组，但类型和它的成员必须在同一组。分组名满足
`[A-Za-z][A-Za-z0-9_]*`，只靠大小写区分的分组会被拒绝。
`Base` 存放运行时支撑和前向声明，也可放置明确选择的支撑类型。

白名单不能覆盖 `@HiddenFromObjC`、Kotlin 可见性或官方映射限制。
标记 private、internal、local 声明，或标记这些类型内部的成员时，会明确报错。
data class 的 `copy`、`componentN` 不会自动开放；Native 必需的运行时声明及
NSObject 的相等性、hash、description 映射不会因此被移除。已有导出继承契约的分发桥接
继续保留，但不会自动展开子类新增加的成员。

### 单例、Companion 和枚举

自动生成的入口需要用 `synthetic` 单独选择：

```kotlin
@ObjCExport(group = "Services", synthetic = ["shared"])
object Settings {
    @ObjCExport
    val version: String = "1.0"
}

@ObjCExport(group = "Models", synthetic = ["companion"])
class Item {
    @ObjCExport(group = "Models", synthetic = ["shared"])
    companion object {
        @ObjCExport
        const val DEFAULT_LIMIT = 10
    }
}

@ObjCExport(group = "Models", synthetic = ["enum:READY", "enum:DONE"])
enum class State { READY, DONE, INTERNAL }
```

支持的角色包括 `shared`、`instance`、`companion`、`enum:ENTRY_NAME`、`values`、`entries`，
以及 `kotlin.Throwable` 上的 `asError`；必须与类型匹配，未知角色会报错。
外层类型的 `companion` 访问器和 Companion 自己的 `shared` 是两个独立入口。
新增枚举成员不会自动导出。枚举映射需要的标准库支撑类型也应明确配置。

### 无法修改源码的外部声明

Framework 模块目录下的 `objc-export.api` 可以补充规则。例如，枚举映射可能需要：

```text
class Base kotlin.Enum
class Base kotlin.Array
protocol Base kotlin.Comparable
```

只添加实际需要的支撑类型。其他规则语法示例：

```text
class Models example.ExternalModel
function Models example.ExternalModel.<init>(kotlin.String)
property Models example.ExternalModel.name read
function Models example.ExternalModel.update(kotlin.String)
synthetic Models example.ExternalModel companion
```

这是语法示例，不代表对任意类都成立的完整配置。属性必须写 `read` 或 `readwrite`。
方法省略参数列表时必须恰好匹配一个声明，新增重载会报错；完整签名包含扩展接收者和
Kotlin 完全限定参数类型。不支持通配符。

注解和文件规则合并，相同规则可共存，分组或访问权限冲突会失败。
纯注解模式可不提供文件；最终没有任何入口时会拒绝空 API。
自定义文件路径：

```kotlin
objcExport {
    policyFile.set(layout.projectDirectory.file("config/ios-api.txt"))
}
```

## 运行原理

这套工具直接参与编译器的结构化导出流程，**不是生成完整公开头文件后，再用正则删除声明**。

编译器插件先收集、合并和校验规则，再复用 Kotlin 官方导出器的映射与命名能力，
同步筛选合成声明和桥接规格。签名引用的类型、父类等需要明确允许；Native 必需的
基础运行时声明和禁止非法构造的 unavailable 声明继续保留。

头文件按 group 渲染。继承关系和 category 生成必要的 import，普通对象引用使用前向声明。
扩展 category 与目标类使用同一组；同一 Kotlin 文件中的顶层声明因共享文件包装类，
也需要使用同一组。分组形成循环依赖时会报错，不会随机决定拆分方式。

编译器输出版本化的 `headers.zip`，Gradle 在**同一个链接任务**的末尾读取它，写入分组
头文件、主头文件、modulemap 和文件清单，并删除过期的自有头文件，不删除其他头文件。
直接链接和依赖该链接任务的打包流程都会经过这一步。

modulemap 输出仅接受已知的 Native 结构，保留 `use` 依赖，未知指令会被拒绝。
文件逐个原子替换，整个 Framework 目录并不是一个事务；链接或文件输出失败后，
该次 Framework 不应交付使用。

## 报告与排查

每个 Framework binary 都有独立的中间目录：

```text
build/objc-export/<target>/<binary>/
├── api.txt       合并规则及实际生成的公开声明
└── headers.zip   交给 Gradle 输出的已渲染头文件
```

候选签名清单默认关闭，需要时显式开启：

```bash
./gradlew -p examples/basic :shared:dumpObjCExportSignatures \
    -PobjcExportDumpSignatures=true
```

便捷任务只为名叫 `iosSimulatorArm64` 的 target 和 `debugFramework` binary 注册。
同一开关也可用于直接链接任务。开启后，`signatures.txt` 才成为链接输出；
关闭开关会使链接输入发生变化，下次链接会清理旧清单。
不带开关调用 dump 任务时，只会提示所需参数，不触发链接。

候选签名不保证声明一定能导出。诊断有 PSI 时提供路径和行号，只有 KLIB 文件信息时提供
文件名，其他情况保留完整签名。报告可能包含使用者的 API 名称和诊断路径，公开附到 issue
前应检查脱敏。中间文件已经存在，不代表 Framework 构建成功。

## 已知边界与维护

- 编译器内部接口随版本变化。升级 Kotlin 后需要适配，并重新验证编译、链接、原生调用和运行行为。
- 命名仍由 Native 决定。从更大的导出范围切换过来时，名称冲突的避让结果可能变化，需要同步原生调用方。
- 不导出不等于删除 Kotlin 实现或所有 Mach-O 符号，也不承诺包体积减少。
- 尚未验证与 SKIE 等其他导出转换插件同时使用，不应假定可组合。
- Framework 构建缓存受 Kotlin Gradle plugin 的限制，KOEK 不强行开启缓存。
  当前独立版本不承诺配置缓存兼容性。
- 当前没有自动 API 基线门禁或运行回归套件。分组结果稳定，也不能自动判断业务划分是否合理。

## 仓库结构

```text
annotations/       注解与访问权限定义
compiler-plugin/   Kotlin 2.2.21 适配、规则校验、桥接过滤和头文件渲染
gradle-plugin/     链接任务接入及 Framework 文件输出
shared/            纯 JVM 中间包协议和原子文件工具
examples/basic/    使用本地 composite build 的通用消费工程
```

共享源码分别编入两个插件 JAR，没有单独的运行时依赖；Gradle 输出层不依赖 Native 编译器 AST。

## 贡献与验证

欢迎提供小而通用的复现工程，说明 Kotlin、Gradle、JDK、Xcode 和 target 版本。
日志、示例中不要包含凭证、私有地址、本机用户名或专有 API 名称。贡献遵循 [MIT](LICENSE)。

准备发布前，应验证允许／禁止接口、协议回调、继承分发、独立头文件导入、过期分组清理、
重复输出一致性，以及计划支持的真机和模拟器构建变体。这是后续验证要求，不代表当前版本
已经通过这些检查。
