# R8 keep rules for the release build (debug builds don't run R8).

# ML Kit (the QR code scanner) creates its component registrars by reflection: their
# class names are listed in the manifest and the no-argument constructor is called.
# firebase-components 16.1.0 keeps the classes but not the constructor, and R8's full
# mode removes it. ML Kit then can't register SharedPrefManager, and
# GmsBarcodeScanning.getClient() crashed with a NullPointerException.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
