package com.iq.usbterminal;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class USBService extends Service {

    private static final String TAG = "UsbService";

    private final IBinder binder = new LocalBinder();

    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbDeviceConnection usbConnection;
    private UsbInterface usbInterface;
    private UsbEndpoint usbEndpointIn;
    private boolean isRunning = false;

    private DatabaseReference firebaseDatabaseRef;

    private static final int NOTIFICATION_ID = 12345;

    private Context context;

    private Handler handler;
    private Runnable locationUpdater;

    private LocationManager locationManager;

    public class LocalBinder extends Binder {
        USBService getService() {
            return USBService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
        usbManager = (UsbManager) getSystemService(USB_SERVICE);

        firebaseDatabaseRef = FirebaseDatabase.getInstance().getReference("usb_data");

        // Set up the location listener
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            Toast.makeText(context, "Checking location permission", Toast.LENGTH_SHORT).show();
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }

            handler = new Handler();
            locationUpdater = new Runnable() {
                @Override
                public void run() {
                    requestLocationUpdates();
                    handler.postDelayed(this, 10000);
                }
            };
            handler.postDelayed(locationUpdater, 0);

        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                connectDevice(device);
            }
        }
        Log.e("USBUploadService", "Service started.");
        Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);

        //Toast.makeText(context, "USBUploadService started", Toast.LENGTH_SHORT).show();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnectDevice();
        handler.removeCallbacks(locationUpdater);
    }

    public void connectDevice(UsbDevice device) {
        if (isRunning) {
            Log.e(TAG, "Service is already running.");
            return;
        }

        if (usbManager.hasPermission(device)) {
            usbDevice = device;
            usbConnection = usbManager.openDevice(usbDevice);

            if (usbConnection != null) {
                usbInterface = usbDevice.getInterface(0);
                usbEndpointIn = usbInterface.getEndpoint(0);

                isRunning = true;
                startUsbCommunication();
            } else {
                Log.e(TAG, "Failed to open USB device connection.");
            }
        } else {
            Log.e(TAG, "Permission denied for USB device.");
        }
    }

    public void disconnectDevice() {
        isRunning = false;

        if (usbConnection != null) {
            usbConnection.releaseInterface(usbInterface);
            usbConnection.close();
            usbConnection = null;
        }
    }

   /* private void startUsbCommunication() {
        new Thread(() -> {
            byte[] requestBytes = new byte[]{0x02, 0x01, 0x31, 0x00, 0x00, 0x00, 0x00, 0x00};
            byte[] responseBuffer = new byte[usbEndpointIn.getMaxPacketSize()];


            UsbEndpoint usbEndpointOut = null;
            for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(i);
                if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                    usbEndpointOut = endpoint;
                    break;
                }
            }

            if (usbEndpointOut == null) {
                Log.e(TAG, "USB OUT endpoint not found.");
                return;
            }

            while (isRunning) {
                int bytesRead = usbConnection.bulkTransfer(usbEndpointIn, responseBuffer, responseBuffer.length, 100);
                if (bytesRead > 0) {

                    String receivedResponse = byteArrayToHexString(responseBuffer, bytesRead);
                    Log.d(TAG, "Received Response: " + receivedResponse);

                    uploadToFirebase("Received Response:" + receivedResponse);

                }


                int bytesSent = usbConnection.bulkTransfer(usbEndpointOut, requestBytes, requestBytes.length, 100);
                if (bytesSent != requestBytes.length) {
                    Log.e(TAG, "Failed to send request.");
                }
            }
        }).start();
    }*/

    private void startUsbCommunication() {
        new Thread(() -> {
            byte[] requestBytes = new byte[]{0x02, 0x01, 0x31, 0x00, 0x00, 0x00, 0x00, 0x00};
            byte[] responseBuffer = new byte[usbEndpointIn.getMaxPacketSize()];

            UsbEndpoint usbEndpointOut = null;
            for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(i);
                if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                    usbEndpointOut = endpoint;
                    break;
                }
            }

            if (usbEndpointOut == null) {
                Log.e(TAG, "USB OUT endpoint not found.");
                return;
            }

            while (isRunning) {
                int bytesSent = usbConnection.bulkTransfer(usbEndpointOut, requestBytes, requestBytes.length, 100);
                if (bytesSent != requestBytes.length) {
                    Log.e(TAG, "Failed to send request.");
                } else {
                    Log.d(TAG, "Request sent successfully.");
                }

                int bytesRead = usbConnection.bulkTransfer(usbEndpointIn, responseBuffer, responseBuffer.length, 100);
                if (bytesRead > 0) {
                    String receivedResponse = byteArrayToHexString(responseBuffer, bytesRead);
                    Log.d(TAG, "Received Response: " + receivedResponse);

                    uploadToFirebase("Received Response:" + receivedResponse);
                }

                try {
                    Thread.sleep(10000); // Sleep for 10 seconds
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }



    private String byteArrayToHexString(byte[] byteArray, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X", byteArray[i]));
            sb.append(" ");
        }
        return sb.toString();
    }

    private void uploadToFirebase(String data) {
        DatabaseReference newChildRef = firebaseDatabaseRef.push();
        newChildRef.setValue(data);
        Log.e("uploaded to Firebase", data);
    }

    private Notification createNotification() {

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "channel_id",
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "channel_id")
                .setContentTitle("USB Upload Service")
                .setContentText("Running in the background")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent);

        return builder.build();
    }

    private void requestLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener, null);
    }

    private final LocationListener locationListener = new LocationListener() {
        boolean hasUpdatedLocation = false;


       /* @Override
        public void onLocationChanged(Location location) {

            Toast.makeText(context, "fetching the GPS coordinates", Toast.LENGTH_SHORT).show();

            if (hasUpdatedLocation) {
                hasUpdatedLocation = true;

                //Toast.makeText(context, "locationManager - 3", Toast.LENGTH_SHORT).show();

                double latitude = location.getLatitude();
                double longitude = location.getLongitude();
                String lat = String.valueOf(latitude);
                String lon = String.valueOf(longitude);

                SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMddHHmmss", Locale.getDefault());
                String dateTime = dateFormat.format(new Date());

                String deviceInfo = String.format("LAT=%s,LON=%s,DATETIME=%s", lat, lon, dateTime);

                uploadToFirebase(deviceInfo);
                Log.e(TAG, "6.");

                Log.e(TAG, "DeviceInfoUpload: " + deviceInfo);
                Toast.makeText(context, "GPS coordinates uploaded to Cloud", Toast.LENGTH_SHORT).show();
            }
        }*/
       @Override
       public void onLocationChanged(Location location) {
           if (!hasUpdatedLocation) {
               hasUpdatedLocation = true;

               double latitude = location.getLatitude();
               double longitude = location.getLongitude();
               float speed = location.getSpeed(); // Speed in meters/second
               float bearing = location.getBearing(); // Bearing in degrees
               int satellitesUsed = location.getExtras().getInt("satellites", -1); // Number of satellites used for fix
               float hdop = location.getExtras().getFloat("hdop", -1.0f); // Horizontal dilution of precision

               // GNSS Fix status
               String gnssFixStatus;
               if (location.getExtras().getBoolean("hasGnssFix", false)) {
                   gnssFixStatus = "GNSS Fix Acquired";
               } else {
                   gnssFixStatus = "No GNSS Fix";
               }

               // GSM Signal strength
               TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
               int gsmSignalStrength = 0; // Signal strength in dBm
               if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                   gsmSignalStrength = telephonyManager.getSignalStrength().getGsmSignalStrength();
               }


               SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMddHHmmss", Locale.getDefault());
               String dateTime = dateFormat.format(new Date());

               String deviceInfo = String.format(
                       "LAT=%s,LON=%s,DATETIME=%s,GNSS=%s,GSM_SIGNAL=%ddBm,SPEED=%.2f m/s,BEARING=%.2f°,SATELLITES_USED=%d,HDOP=%.2f",
                       latitude, longitude, dateTime, gnssFixStatus, gsmSignalStrength, speed, bearing, satellitesUsed, hdop);

               uploadToFirebase(deviceInfo);
               Log.e(TAG, "DeviceInfoUpload: " + deviceInfo);
               Toast.makeText(context, "GPS coordinates uploaded to Cloud", Toast.LENGTH_SHORT).show();
           }
       }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {

        }

        @Override
        public void onProviderEnabled(String provider) {

        }

        @Override
        public void onProviderDisabled(String provider) {

        }
    };
}