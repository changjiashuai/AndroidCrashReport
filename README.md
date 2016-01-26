# AndroidCrashReport
>Android Crash Report

### Repository

>Add this in your root `build.gradle` file (**not** your module `build.gradle` file):

``` Gradle
allprojects {
	repositories {
		...
		 maven {
                url "http://dl.bintray.com/changjiashuai/maven"
         }
	}
}
```

### Dependency

>Add this to your module's `build.gradle` file:

```Gradle
dependencies {
    compile fileTree(include: ['*.jar'], dir: 'libs')
    testCompile 'junit:junit:4.12'
    compile 'com.android.support:appcompat-v7:23.1.1'
    ...
    compile 'io.github.changjiashuai.crashreport:android-crashreport:0.0.1'
    ...
}
```


##Summary

####CrashReport
