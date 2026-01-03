import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.zaext.nicehckcontroller"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zaext.nicehckcontroller"
        minSdk = 23
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    applicationVariants.all {
        outputs.all {
            val project = "NiceHCKController"
            val versionName = versionName
            val versionCode = versionCode
            val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val buildTypeName = buildType.name // <-- 获取构建类型名称

            // 定义你的新文件名格式
            val newApkName = "${project}_v${versionName}_${versionCode}_${date}_${buildTypeName}.apk"

            (this as BaseVariantOutputImpl).outputFileName = newApkName
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {

        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.elvishew.xlog)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}