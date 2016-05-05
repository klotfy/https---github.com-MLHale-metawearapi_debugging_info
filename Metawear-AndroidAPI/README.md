# MetaWear Android API #
The MetaWear Android API is a library for interacting with the MetaWear board on an Android device.  A minimum of Android 4.3 (SDK 18) is required to use this library however some features will not properly function due to the underlying Bluetooth LE implementation.  For the best results, it is recommended that users be on **Android 4.4 (SDK 19) or higher**.

More information on MetaWear platform in on the MbientLab product page ([https://mbientlab.com/product/](https://mbientlab.com/product/)).

# Setup  #
## Adding Compile Dependency ##
To add the library to your project, first, update the repositories closure to include the MbientLab Ivy Repo in the project's *build.gradle* file.

```gradle
repositories {
    ivy {
        url "http://ivyrep.mbientlab.com"
        layout "gradle"
    }
}
```

Then, add the compile element to the dependencies closure in the module's *build.gradle* file.

```gradle
dependencies {
    compile 'com.mbientlab:metawear:2.5.9'
}
```

## Attaching Source Code ##
The MetaWear API aar comes packaged with the library's source code.  To associate the source code with the AAR package:  

1. Switch the project view to display the project structure  
2. Expand the "External Libraries" tab  
3. Right click "metawear-${version}" and select "Library Properties"  
4. Press the add button (green plus icon) and select "sources.jar" under the "jar" tab  

## Declaring the Service ##
Once your project has synced with the updated Gradle files, declare the MetaWear Bluetooth LE service in the module's *AndroidManifest.xml* file.
```xml
<application
    android:allowBackup="true"
    android:icon="@drawable/ic_launcher"
    android:label="@string/app_name"
    android:theme="@style/AppTheme" >

    <service android:name="com.mbientlab.metawear.MetaWearBleService" />
    <!-- Other application info below i.e. activity definitions -->
</application>
```

## Binding the Service ##
Lastly, bind the service in your application and retrain a reference to the service's LocalBinder class.  This can be done in any activity or fragment that needs access to a MetaWearBoard object.

```java
import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.os.IBinder;

import com.mbientlab.metawear.MetaWearBleService;

public class ExampleActivity extends Activity implements ServiceConnection {
    private MetaWearBleService.LocalBinder serviceBinder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ///< Bind the service when the activity is created
        getApplicationContext().bindService(new Intent(this, MetaWearBleService.class),
                this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        ///< Unbind the service when the activity is destroyed
        getApplicationContext().unbindService(this);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        ///< Typecast the binder to the service's LocalBinder class
        serviceBinder = (MetaWearBleService.LocalBinder) service;
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) { }
}
```
With that, you are ready to interact with your board.  Further documentation and guides are available on the MbientLab web page ([https://mbientlab.com/androiddocs/](https://mbientlab.com/androiddocs/)).  Sample code is available both in the [Example](https://github.com/mbientlab/Metawear-AndroidAPI/tree/master/example/src/main/java/com/mbientlab/metawear/example/MainActivity.java) module and the MetaWear [Android app](https://github.com/mbientlab/Metawear-SampleAndroidApp).
