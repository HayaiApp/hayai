import yokai.build.configureAndroid
import yokai.build.configureTest

plugins {
    id("com.android.application")
}

android {
    defaultConfig {
        targetSdk = AndroidConfig.TARGET_SDK
    }
    configureAndroid(this)
    configureTest()
}
