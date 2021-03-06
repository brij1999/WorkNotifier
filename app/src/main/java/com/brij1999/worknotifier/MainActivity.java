package com.brij1999.worknotifier;

import static com.brij1999.worknotifier.WorkNotifierListenerService.SERVICE_NOTIFICATION_ID;
import static com.brij1999.worknotifier.WorkNotifierListenerService.WORKNOTIFIER_HIDE_MONITOR_NTF;
import static com.brij1999.worknotifier.WorkNotifierListenerService.WORKNOTIFIER_LISTENER_ACTIVE;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomappbar.BottomAppBar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners";
    private static final String ACTION_NOTIFICATION_LISTENER_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";

    AppManager appManager;
    Logger logger;
    AlertDialog enableNotificationListenerAlertDialog;

    BottomAppBar btmAppBar;
    NotificationManager mNotificationManager;
    HomeFragment homeFragment = new HomeFragment();
    SettingsFragment settingsFragment = new SettingsFragment();

    private TinyDB tinydb;
    public static String PACKAGE_NAME;
    public static final String NOTIFICATION_CHANNEL_ID = "WorkNotifierNotification";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        appManager = AppManager.getInstance(getApplicationContext());
        logger = Logger.getInstance(getApplicationContext());
        tinydb = new TinyDB(getApplicationContext());
        PACKAGE_NAME = getApplicationContext().getPackageName();
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // Fragments Setup
        getSupportFragmentManager().beginTransaction().replace(R.id.frameContainer, homeFragment).commit();
        btmAppBar = findViewById(R.id.bottomAppBar);
        btmAppBar.setNavigationOnClickListener((item) -> getSupportFragmentManager().beginTransaction().replace(R.id.frameContainer, homeFragment).commit());
        btmAppBar.setOnMenuItemClickListener((item) -> {
            switch (item.getItemId()) {
                case R.id.home:
                    getSupportFragmentManager().beginTransaction().replace(R.id.frameContainer, homeFragment).commit();
                    return true;
                case R.id.settings:
                    getSupportFragmentManager().beginTransaction().replace(R.id.frameContainer, settingsFragment).commit();
                    return true;
            }
            return false;
        });

        // Get intent, action and MIME type
        Intent intent = getIntent();
        if (intent.getType() != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            if(intent.getType().contains("text")) {
                String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
                addURL(sharedText);
            }
        }

        // If the user did not turn the notification listener service on we prompt him to do so
        if(!isNotificationServiceEnabled()){
            enableNotificationListenerAlertDialog = buildNotificationServiceAlertDialog();
            enableNotificationListenerAlertDialog.show();
        }

        FloatingActionButton serviceBtn = findViewById(R.id.serviceBtn);
        if(!tinydb.getBoolean(WORKNOTIFIER_LISTENER_ACTIVE)) {
            serviceBtn.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.colorOn)));
        } else {
            serviceBtn.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.colorOff)));
        }
        serviceBtn.setOnClickListener((v) -> {
            if(!tinydb.getBoolean(WORKNOTIFIER_LISTENER_ACTIVE)) {
                //start
                tinydb.putBoolean(WORKNOTIFIER_LISTENER_ACTIVE, true);
                serviceBtn.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.colorOff)));
                Toast.makeText(MainActivity.this, "Notification Monitoring Enabled", Toast.LENGTH_SHORT).show();
                logger.log("MainActivity", "onCreate-onClick", "Service Started");

                if(!tinydb.getBoolean(WORKNOTIFIER_HIDE_MONITOR_NTF)) {
                    Intent cIntent = new Intent(getApplicationContext(), MainActivity.class);
                    cIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    PendingIntent cPendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, cIntent, PendingIntent.FLAG_IMMUTABLE);

                    Notification.Builder notification = new Notification.Builder(getApplicationContext(), MainActivity.NOTIFICATION_CHANNEL_ID)
                            .setContentTitle("WorkNotifier Enabled")
                            .setContentText("Forwarding notifications of monitored apps to watch")
                            .setSmallIcon(getApplicationInfo().icon)
                            .setContentIntent(cPendingIntent)
                            .setOngoing(true);

                    mNotificationManager.notify(SERVICE_NOTIFICATION_ID, notification.build());
                }
            } else {
                //stop
                tinydb.putBoolean(WORKNOTIFIER_LISTENER_ACTIVE, false);
                serviceBtn.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.colorOn)));
                Toast.makeText(MainActivity.this, "Notification Monitoring Disabled", Toast.LENGTH_SHORT).show();
                logger.log("MainActivity", "onCreate-onClick", "Service Stopped");

                if(!tinydb.getBoolean(WORKNOTIFIER_HIDE_MONITOR_NTF)) {
                    mNotificationManager.cancel(SERVICE_NOTIFICATION_ID);
                }
            }
        });

        // Create Notification channel to show notifications
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "WorkNotifier Notification", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("All Work Profile Notifications Captured");
        mNotificationManager.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (enableNotificationListenerAlertDialog != null) {
            enableNotificationListenerAlertDialog.dismiss();
            enableNotificationListenerAlertDialog = null;
        }
    }

    public void addURL(String input) {
        try {
            URL url = new URL(input);
            Uri uri = Uri.parse(url.toString());
            String pkgName = uri.getQueryParameter("id");

            if(!url.getHost().equals("play.google.com")) {
                Toast.makeText(MainActivity.this, "Not a play-store URL", Toast.LENGTH_SHORT).show();
            } else if(appManager.containsPkg(pkgName)) {
                Toast.makeText(MainActivity.this, "App is already being monitored", Toast.LENGTH_SHORT).show();
            } else {
                ExecutorService executorService = Executors.newSingleThreadExecutor();
                AppInfo app = new AppInfo();
                app.setAppPkg(pkgName);

                executorService.execute(() -> {
                    URL iconURL = null;
                    String appName = "";
                    try {
                        Document doc = Jsoup.connect("https://play.google.com/store/apps/details?id="+pkgName).get();
                        Element image = doc.select("img[alt=\"Icon image\"]").first();
                        Element name = doc.select("[itemprop=\"name\"] span").first();

                        iconURL = (image==null) ? new URL("https://github.com/brij1999/MiscFiles/raw/master/android_icon.png") : new URL(image.attr("src").replaceAll("=s180-rw", "=s360-rw"));
                        appName = (name==null) ? pkgName : name.text();
                    } catch (NullPointerException | IOException e) {
                        e.printStackTrace();
                    }
                    app.setAppName(appName);
                    app.setAppIcon(iconURL);
                    appManager.addApp(app);
                    logger.log("MainActivity", "addURL", "App Added: "+app);

                    runOnUiThread(() -> {
                        homeFragment.adapter.notifyItemInserted(appManager.size()-1);
                        Toast.makeText(MainActivity.this, app.getAppName()+" is now being monitored", Toast.LENGTH_SHORT).show();
                    });
                });
            }
        } catch (MalformedURLException e) {
            Toast.makeText(MainActivity.this, "Not a valid URL", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }

    }


    /**
     * Is Notification Service Enabled.
     * Verifies if the notification listener service is enabled.
     * Got it from: https://github.com/kpbird/NotificationListenerService-Example/blob/master/NLSExample/src/main/java/com/kpbird/nlsexample/NLService.java
     * @return True if enabled, false otherwise.
     */
    private boolean isNotificationServiceEnabled(){
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(),
                ENABLED_NOTIFICATION_LISTENERS);
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.getPackageName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Build Notification Listener Alert Dialog.
     * Builds the alert dialog that pops up if the user has not turned
     * the Notification Listener Service on yet.
     * @return An alert dialog which leads to the notification enabling screen
     */
    private AlertDialog buildNotificationServiceAlertDialog(){
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this)
                .setTitle(R.string.notification_listener_dialogue_title)
                .setMessage(R.string.notification_listener_dialogue_explanation)
                .setCancelable(false)
                .setPositiveButton(R.string.yes,
                    (dialog, id) -> startActivity(new Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                .setNegativeButton(R.string.no,
                    (dialog, id) -> finishAndRemoveTask());
        return(alertDialogBuilder.create());
    }
}