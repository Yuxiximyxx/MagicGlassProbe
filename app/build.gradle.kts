plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 自有 release 签名。*.jks 在 .gitignore 里，CI 由 secret 解码到 app/signing/；
// 本地缺失时构建脚本会在签名校验阶段报错，属预期（别把 keystore 提交进仓库）。
val releaseKeystore = file("signing/magicglassprobe.jks")

android {
    namespace = "io.github.yuxiximyxx.magicglassprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.yuxiximyxx.magicglassprobe"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.5.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = releaseKeystore
            storePassword = "magicglass123"
            keyAlias = "magicglassprobe"
            keyPassword = "magicglass123"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            // 关闭混淆：LSPosed 靠 java_init.list 里的类全名反射加载入口，
            // 混淆改名 = 构建成功但模块静默不生效。
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    // libxposed：运行期由 LSPosed 注入，只编译期可见
    compileOnly("io.github.libxposed:api:102.0.0")
}
