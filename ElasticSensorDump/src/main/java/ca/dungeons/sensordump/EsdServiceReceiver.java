package ca.dungeons.sensordump;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

    /**
    * Created by Gurtok on 9/19/2017.
    * Broadcast receiver for the EsdServiceManager.
    */
public class EsdServiceReceiver extends BroadcastReceiver {

        /** ID this class in LogCat. */
    private static final String logTag = "EsdServiceReceiver";

        /** Instance of ESD service manager. */
    private final EsdServiceManager esdServiceManager;

        /** Filter for the broadcast receiver. */
    final IntentFilter messageFilter = new IntentFilter();

/* Sensor toggles. */
        /** Intent action address: Boolean - If we are recording PHONE sensor data. */
    public final static String SENSOR_MESSAGE = "esd.intent.action.message.SENSOR";

        /** Intent action address: Boolean - If we are recording GPS sensor data. */
    public final static String GPS_MESSAGE = "esd.intent.action.message.GPS";

        /** Intent action address: Boolean - If we are recording AUDIO sensor data. */
    public final static String AUDIO_MESSAGE = "esd.intent.action.message.AUDIO";

/* Interval rate change from main UI. */
        /**  Intent action address: integer - Rate change from user. */
    public final static String INTERVAL = "esd.intent.action.message.INTERVAL";

        /** Default constructor:
        * This class is instantiated by the service manager, thus it passes itself to this class. */
    public EsdServiceReceiver( EsdServiceManager passedManagerObj ) {
        esdServiceManager = passedManagerObj;
        addFilters();
    }

        /** Assembles the message filter for this receiver. */
    private void addFilters(){
        messageFilter.addAction( SENSOR_MESSAGE );
        messageFilter.addAction( GPS_MESSAGE );
        messageFilter.addAction( AUDIO_MESSAGE );
        messageFilter.addAction( INTERVAL );
    }

        /** Main point of contact for the service manager.
        *  All information and requests are handled here.
        */
    @Override
        public void onReceive(Context context, Intent intent) {
            Intent messageIntent = new Intent();

            switch( intent.getAction() ){

                case (SENSOR_MESSAGE):
                    if (intent.getBooleanExtra("sensorPower", true)) {
                        esdServiceManager.startLogging();
                    } else {
                        esdServiceManager.stopLogging();
                    }
                    break;

                case GPS_MESSAGE:
                    esdServiceManager.gpsLogging = intent.getBooleanExtra("gpsPower", false);
                    messageIntent.setAction( SensorRunnable.GPS_POWER );
                    messageIntent.putExtra("gpsPower", esdServiceManager.gpsLogging);
                    esdServiceManager.sendBroadcast(messageIntent);
                    break;

                case AUDIO_MESSAGE:
                    esdServiceManager.audioLogging = intent.getBooleanExtra("audioPower", false);
                    messageIntent = new Intent(SensorRunnable.AUDIO_POWER);
                    messageIntent.putExtra("audioPower", esdServiceManager.audioLogging);
                    esdServiceManager.sendBroadcast(messageIntent);
                    break;

                case INTERVAL:
                    esdServiceManager.sensorRefreshRate = intent.getIntExtra("sensorInterval", 250);
                    messageIntent = new Intent(SensorRunnable.INTERVAL);
                    messageIntent.putExtra("sensorInterval", esdServiceManager.sensorRefreshRate);
                    esdServiceManager.sendBroadcast(messageIntent);
                    break;

                default:
                    Log.e(logTag , "Received bad information from ACTION intent." );
                    break;
            }
        }



}

















