# R8 rules for the release build. Libraries ship their own consumer rules for the
# reflection they own; this file holds what only the app knows.

# Polar BLE SDK
#
# BleGattFactory creates the GATT clients with getDeclaredConstructor and newInstance
# from a set of class literals, so R8 cannot see that those constructors are used.
-keepclassmembers class * extends com.polar.androidcommunications.api.ble.model.gatt.BleGattBase {
    <init>(...);
}

# The SDK reads assets/polar_device_capabilities.json with Gson, which maps JSON keys
# to field names and instantiates the model with its no-argument constructor. Renamed
# fields or a missing constructor would silently fall back to the defaults.
-keepclassmembers class com.polar.androidcommunications.api.ble.model.polar.** {
    <fields>;
    <init>();
}

# The protobuf lite runtime instantiates generated messages reflectively; the SDK's
# consumer rules already keep them, so no rule is repeated here.

# WorkManager
#
# WorkManager stores the worker class name in its database and instantiates it later,
# so the name and the constructor have to survive. Its consumer rules keep both for
# every ListenableWorker subclass, which covers UploadWorker and DeleteWorker.

# OkHttp and RxJava
#
# Both ship the rules they need (optional platform classes in OkHttp, the JVM Flow
# API that Android does not have in RxJava); nothing has to be repeated here.

# kotlinx.serialization
#
# The serializers are generated, not looked up by name, and the library ships the rules
# that keep their companions and descriptor fields.
