package com.samples.flironecamera;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

public class ProcessingService extends Service {

    private Timer timer = new Timer();


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        System.out.println("in create");
        super.onCreate();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    String key = SensorHandler.Authenticate();
                    SensorHandler.QuerySamples(key);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }, 0, 5000);//5 Seconds
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        //shutdownService();

    }

}
