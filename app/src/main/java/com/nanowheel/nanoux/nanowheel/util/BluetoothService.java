package com.nanowheel.nanoux.nanowheel.util;

import android.Manifest;
import android.animation.Animator;
import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Service;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import androidx.databinding.Observable;
import androidx.databinding.ObservableField;

import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Chronometer;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.github.lzyzsd.circleprogress.DonutProgress;
import com.nanowheel.nanoux.nanowheel.FragmentOverview;
import com.nanowheel.nanoux.nanowheel.App;
import com.nanowheel.nanoux.nanowheel.FragmentDiagnotics;
import com.nanowheel.nanoux.nanowheel.MainActivity;
import com.nanowheel.nanoux.nanowheel.R;
import com.nanowheel.nanoux.nanowheel.WidgetUpdater;
import com.nanowheel.nanoux.nanowheel.model.OWDevice;

import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static com.nanowheel.nanoux.nanowheel.model.OWDevice.MockOnewheelCharacteristicBatteryInitial;
import static com.nanowheel.nanoux.nanowheel.model.OWDevice.MockOnewheelCharacteristicCharging;
import static com.nanowheel.nanoux.nanowheel.model.OWDevice.MockOnewheelCharacteristicMaxSpeed;
import static com.nanowheel.nanoux.nanowheel.model.OWDevice.MockOnewheelCharacteristicOdometer;
import static com.nanowheel.nanoux.nanowheel.model.OWDevice.MockOnewheelCharacteristicOdometerCharge;
import static com.nanowheel.nanoux.nanowheel.model.OWDevice.MockOnewheelCharacteristicOdometerRange;
import static com.nanowheel.nanoux.nanowheel.model.OWDevice.MockOnewheelCharacteristicSpeed;
import static com.nanowheel.nanoux.nanowheel.model.OWDevice.OnewheelCharacteristicBatteryRemaining;
import static com.nanowheel.nanoux.nanowheel.model.OWDevice.OnewheelCharacteristicLifetimeOdometer;
import static com.nanowheel.nanoux.nanowheel.model.OWDevice.OnewheelCharacteristicOdometer;
import static com.nanowheel.nanoux.nanowheel.model.OWDevice.OnewheelCharacteristicRidingMode;
import static com.nanowheel.nanoux.nanowheel.model.OWDevice.OnewheelCharacteristicSpeedRpm;

public class BluetoothService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener  {

    public static boolean isExist = false;
    static int lastSpeed = -1;
    public static OWDevice mOWDevice;
    public static Context mContext;
    public static Application mApplication;
    public static BluetoothService mService;
    static BluetoothUtil bluetoothUtil;

    private WindowManager mWindowManager;
    private View mChatHeadView;

    public static ObservableField<String> isOWDevice = new ObservableField<>();

    public static void  createService(Context context){

        if (!isExist) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(new Intent(context, BluetoothService.class));
            }else{
                context.startService(new Intent(context, BluetoothService.class));
            }
        }
    }
    public static void killService(Context context, boolean reboot){
        if (isExist) {
            context.stopService(new Intent(context, BluetoothService.class));
            if (reboot){
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(new Intent(context, BluetoothService.class));
                }else{
                    context.startService(new Intent(context, BluetoothService.class));
                }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startid) {
        mService = this;

        startForeground(NotificationBuilder.SATICJOBID,NotificationBuilder.createScanningNot(this,"Scanning...",false));

        //NotificationBuilder.test(this);

        isExist = true;
        if (App.INSTANCE == null) {
            App.INSTANCE = new App();
        }
        App.INSTANCE.getSharedPreferences(this).setStatusMode(1);

        if (App.INSTANCE == null) {
            App.INSTANCE = new App();
        }
        mContext = this;
        mApplication = getApplication();

        setupOWDevice();
        //FragmentOverview.setupUI();
        //FragmentDiagnotics.setBinding();
        setupStatusCallbacks();
        NotificationBuilder.setupCallabcks(this);

        SharedPreferencesUtil test = App.INSTANCE.getSharedPreferences(this);
        test.registerListener(this);

        Scan();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopService();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    public void stopService(){
        isExist = false;
        mService = null;

        getBluetoothUtil().disconnect();
        removeBluetoothUtil();
        //deviceConnectedTimer(false);

        WidgetUpdater.endLog();
        App.INSTANCE.getSharedPreferences(this).setStatusMode(0);
        App.INSTANCE.getSharedPreferences(this).removeListener(this);
        mOWDevice = null;
        isOWDevice.set("false");

        endFloater();

        updateBatteryWidgets();
        updateSpeedWidgets();
        updateRangeWidgets();
    }


    private void setupOWDevice() {
        mOWDevice = new OWDevice();


        mOWDevice.setupCharacteristics(this);
        mOWDevice.isConnected.set(false);

        getBluetoothUtil().init(this, mOWDevice);
        isOWDevice.set("true");

    }
    int lastBat = -1;
    private void setupStatusCallbacks(){
        mOWDevice.characteristics.get(OnewheelCharacteristicBatteryRemaining).value.addOnPropertyChangedCallback(new Observable.OnPropertyChangedCallback() {
            @Override
            public void onPropertyChanged(Observable observable, int i) {
                mOWDevice.batteryChange(mContext);
                updateBatteryWidgets();

                int battery = Util.parseI(mOWDevice.characteristics.get(OnewheelCharacteristicBatteryRemaining).value.get());
                if ((lastBat == -1 || lastBat > battery) && mOWDevice.characteristics.get(MockOnewheelCharacteristicCharging).value.get() == "false"){
                    lastBat = battery;
                    WidgetUpdater.markLog();
                }else if (mOWDevice.characteristics.get(MockOnewheelCharacteristicCharging).value.get() == "true"){
                    lastBat = -1;
                }

                if (mChatHeadView != null){
                    DonutProgress prog = mChatHeadView.findViewById(R.id.donut);
                    prog.setProgress(battery);
                    prog.setText(battery + "%");
                }
            }
        });

        mOWDevice.characteristics.get(MockOnewheelCharacteristicSpeed).value.addOnPropertyChangedCallback(new Observable.OnPropertyChangedCallback() {
            @Override
            public void onPropertyChanged(Observable observable, int i) {
                if (mOWDevice != null && mOWDevice.characteristics.get(MockOnewheelCharacteristicSpeed).value.get() != null) {
                    int speed = Math.round(Util.parseF(mOWDevice.characteristics.get(MockOnewheelCharacteristicSpeed).value.get()));
                    if (speed != lastSpeed) {
                        updateSpeedWidgets();
                    }
                    lastSpeed = speed;
                }

            }
        });

        mOWDevice.characteristics.get(MockOnewheelCharacteristicOdometerRange).value.addOnPropertyChangedCallback(new Observable.OnPropertyChangedCallback() {
            @Override
            public void onPropertyChanged(Observable observable, int i) {
            updateRangeWidgets();
            }
        });

        mOWDevice.characteristics.get(OnewheelCharacteristicOdometer).value.addOnPropertyChangedCallback(new Observable.OnPropertyChangedCallback() {
            @Override
            public void onPropertyChanged(Observable observable, int i) {
                WidgetUpdater.revLog();
            }
        });
    }

    private void setupFloatingStatusCallbacks(){
        if (mChatHeadView == null){
            return;
        }
        final View view = mChatHeadView;
        mOWDevice.characteristics.get(MockOnewheelCharacteristicOdometer).value.addOnPropertyChangedCallback(new Observable.OnPropertyChangedCallback() {
            @Override
            public void onPropertyChanged(Observable observable, int i) {
                mOWDevice.odometerChange(view,R.id.textViewTripNum);
            }
        });
        mOWDevice.characteristics.get(MockOnewheelCharacteristicOdometerCharge).value.addOnPropertyChangedCallback(new Observable.OnPropertyChangedCallback() {
            @Override
            public void onPropertyChanged(Observable observable, int i) {
                mOWDevice.chargeOdometerChange(view,R.id.textViewChargeNum);
            }
        });
        mOWDevice.characteristics.get(MockOnewheelCharacteristicOdometerRange).value.addOnPropertyChangedCallback(new Observable.OnPropertyChangedCallback() {
            @Override
            public void onPropertyChanged(Observable observable, int i) {
                mOWDevice.rangeOdometerChange(view,R.id.textViewRangeNum);
            }
        });
        mOWDevice.characteristics.get(OnewheelCharacteristicLifetimeOdometer).value.addOnPropertyChangedCallback(new Observable.OnPropertyChangedCallback() {
            @Override
            public void onPropertyChanged(Observable observable, int i) {
                mOWDevice.lifeOdometerChange(view,R.id.textViewTotalNum);
            }
        });

        refreshUI(view);
    }

    public void refreshUI(View view){
        mOWDevice.odometerChange(view,R.id.textViewTripNum);
        mOWDevice.chargeOdometerChange(view,R.id.textViewChargeNum);
        mOWDevice.rangeOdometerChange(view,R.id.textViewRangeNum);
        mOWDevice.lifeOdometerChange(view,R.id.textViewTotalNum);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        switch (key) {
            case SharedPreferencesUtil.STATUS_MODE:
                int hi = sharedPreferences.getInt(key,0);
                if (hi == 2){//basically connection callback now I guess
                    if (mChatHeadView == null){
                        if (App.INSTANCE.getSharedPreferences(this).isFloatStat() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))){
                            startFloater();
                        }
                    }
                    if(bluetoothUtil.isObfucked()) {
                        deviceConnectedTimer();
                    }
                }
                break;

            case SharedPreferencesUtil.FLOAT_STAT:
                if (App.INSTANCE.getSharedPreferences(this).isFloatStat()&& mChatHeadView == null && App.INSTANCE.getSharedPreferences(this).getStatusMode() == 2 && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))){
                    startFloater();
                }else if (!App.INSTANCE.getSharedPreferences(this).isFloatStat() && mChatHeadView != null){
                    endFloater();
                }
                break;

        }

    }



    public static BluetoothUtil getBluetoothUtil() {
        if (bluetoothUtil == null) {
            bluetoothUtil = new BluetoothUtilImpl();
        }

        return bluetoothUtil;
    }
    public static void removeBluetoothUtil(){
        bluetoothUtil = null;
    }

    public void Scan(){
        boolean canDo = getPermissions();
        if (canDo){
            bluetoothUtil.startScanning();
        }else{
            stopForeground(true);
            stopSelf();
        }
    }

    private boolean getPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                return true;
            } else {
                return false;
            }
        }else{
            return true;
        }
    }
    public static void updateNotification(int percent, Context context){
        NotificationBuilder.createStatusNot(percent, context);
        WidgetUpdater.setupLog(mApplication);
    }

    public void deviceConnectedTimer() {
        final int repeatTime = 15000;
        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if(isExist && App.INSTANCE.getSharedPreferences().getStatusMode() == 2) {
                    mOWDevice.sendKeyChallengeForGemini(getBluetoothUtil());
                    handler.postDelayed(this, repeatTime);
                }
            }
        }, repeatTime);
    }

    public void updateBatteryWidgets(){
        WidgetUpdater.updateBattery(getApplication());
    }
    public void updateSpeedWidgets(){
        WidgetUpdater.updateSpeed(getApplication());
    }
    public void updateRangeWidgets(){
        WidgetUpdater.updateRange(getApplication());
    }

    public void endFloater(){
        if (mChatHeadView != null) {
            mChatHeadView.setAlpha(1);
            mChatHeadView.animate().alpha(0).setDuration(1000).setListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animator) {

                }

                @Override
                public void onAnimationEnd(Animator animator) {
                    mWindowManager.removeView(mChatHeadView);
                    mChatHeadView = null;
                    mWindowManager = null;

                    if (App.INSTANCE.getSharedPreferences(getApplicationContext()).isFloatStat()&& App.INSTANCE.getSharedPreferences(getApplicationContext()).getStatusMode() == 2 && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(getApplicationContext()))){
                        startFloater();
                    }
                }

                @Override
                public void onAnimationCancel(Animator animator) {
                }

                @Override
                public void onAnimationRepeat(Animator animator) {

                }
            });
        }else {
            mChatHeadView = null;
            mWindowManager = null;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    public void startFloater(){
        mChatHeadView = LayoutInflater.from(this).inflate(R.layout.floater, null);

        final WindowManager.LayoutParams params;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
        }else{
            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
        }

        params.gravity = Gravity.TOP | Gravity.LEFT;
        float[] pos = App.INSTANCE.getSharedPreferences(this).getFloatPos();
        params.x = (int)pos[0];
        params.y = (int)pos[1];

        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mWindowManager.addView(mChatHeadView, params);

        DonutProgress prog = mChatHeadView.findViewById(R.id.donut);
        prog.setUnfinishedStrokeWidth(dipToPix(5));
        prog.setUnfinishedStrokeColor(getResources().getColor(R.color.text_color_secondary));
        prog.setFinishedStrokeWidth(dipToPix(5));
        prog.setFinishedStrokeColor(getResources().getColor(R.color.colorAccent));
        prog.setText("0%");
        prog.setTextSize(dipToPix(15));
        prog.setStartingDegree(90);

        String myah = mOWDevice.characteristics.get(OnewheelCharacteristicBatteryRemaining).value.get();
        if (myah != null){
            int battery = Util.parseI(myah);
            prog.setProgress(battery);
            prog.setText(battery + "%");
        }

        setupFloatingStatusCallbacks();

        final RelativeLayout chatHeadImage = mChatHeadView.findViewById(R.id.layout);
        final RelativeLayout expand = mChatHeadView.findViewById(R.id.drawer);
        if (App.INSTANCE.getSharedPreferences(this).isFloatExtend()){
            expand.setScaleY(1);
            expand.setVisibility(View.VISIBLE);
        }else{
            expand.setVisibility(View.GONE);
        }
        chatHeadImage.setAlpha(0);
        chatHeadImage.animate().alpha(1).setDuration(1000);
        chatHeadImage.setOnTouchListener(new View.OnTouchListener() {
            private int lastAction;
            private int initialX;
            private int initialY;

            @Override
            public int hashCode() {
                return super.hashCode();
            }

            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        //get the touch location
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();

                        lastAction = event.getAction();
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (Math.abs(event.getRawX() - initialTouchX) <= 3 && Math.abs(event.getRawY() - initialTouchY) <= 3) {
                            if (expand.getVisibility() == View.VISIBLE){
                                App.INSTANCE.getSharedPreferences(getApplicationContext()).setFloatExtend(false);
                                expand.setScaleY(1);
                                expand.animate().scaleY(0).setDuration(1000).setListener(new Animator.AnimatorListener() {
                                    @Override
                                    public void onAnimationStart(Animator animator) {

                                    }

                                    @Override
                                    public void onAnimationEnd(Animator animator) {
                                        expand.setVisibility(View.GONE);
                                    }

                                    @Override
                                    public void onAnimationCancel(Animator animator) {

                                    }

                                    @Override
                                    public void onAnimationRepeat(Animator animator) {

                                    }
                                });
                            }else{
                                App.INSTANCE.getSharedPreferences(getApplicationContext()).setFloatExtend(true);
                                expand.setVisibility(View.VISIBLE);
                                expand.setScaleY(0);
                                expand.animate().scaleY(1).setDuration(1000).setListener(new Animator.AnimatorListener() {
                                    @Override
                                    public void onAnimationStart(Animator animator) {

                                    }

                                    @Override
                                    public void onAnimationEnd(Animator animator) {
                                        expand.setVisibility(View.VISIBLE);
                                        expand.setScaleY(1);
                                    }

                                    @Override
                                    public void onAnimationCancel(Animator animator) {

                                    }

                                    @Override
                                    public void onAnimationRepeat(Animator animator) {

                                    }
                                });
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        //Calculate the X and Y coordinates of the view.
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);

                        App.INSTANCE.getSharedPreferences(v.getContext()).setFloatPos(params.x,params.y);

                        //Update the layout with new X & Y coordinate
                        if (mWindowManager != null) {
                            mWindowManager.updateViewLayout(mChatHeadView, params);
                        }
                        lastAction = event.getAction();
                        return true;
                }
                return false;
            }

        });
    }

    public float dipToPix(float dip){
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip, getResources().getDisplayMetrics());
    }

}