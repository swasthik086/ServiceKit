package com.iq.usbterminal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.hoho.android.usbserial.driver.SerialTimeoutException;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }

    private final BroadcastReceiver broadcastReceiver;
    private int deviceId, portNum, baudRate;
    private UsbSerialPort usbSerialPort;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private ControlLines controlLines;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean controlLinesEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    private DatabaseReference firebaseDatabaseRef;

    private String serverAddress = "cvfeed.mytvs.in";
    private int serverPort = 7788;

    private Socket socket;

    private OutputStream outputStream;

    private PrintWriter out;

    private Timer timer;

    private LocationManager locationManager;

    private final Executor executor = Executors.newSingleThreadExecutor();

    private Context context;

    private Handler handler;
    private Runnable locationUpdater;

    private Double lat=0.0;
    private Double lon=0.0;
    private float bearingSt=0;
    private float speedSt=0;
    private int satellite=0;
    private float hdopSt=0;

    // dateTime, gnssFixStatus, gsmSignalStrength
    private String dateTimeSt="";
    private String gnssFixStatusSt="";
    private int gsmSignalStrengthSt=0;
    private SpannableStringBuilder spn_new;
    private int count =0;
    private String reqstr="";

    private int first_service=0;

    private String data_new="";

    private boolean initializationDone = false;

    private int request_index=0;

    private int UDSresponse=0;

    private String spn = "";

    private int receive_complete=0;

    private ExecutorService executorService;

    private int lineCount = 0;

    private boolean newSequence = false;

    private     int buffercount =0;

    private int blockCount = 0;
    private boolean reset = false;

    private boolean initbuffer = false;

    private boolean lastBlock = false;

    private int chinkslength, chunkSize;

    private int lineNumber = 7685;
    private int lineNumberAPP1 = 0;

    private  int sequence = 0;

    private String hexString;



    public TerminalFragment() {

        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(Constants.INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    connect(granted);
                }
            }
        };
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");

        spn_new = new SpannableStringBuilder();

        firebaseDatabaseRef = FirebaseDatabase.getInstance().getReference("usb_data");


        locationManager = (LocationManager) requireActivity().getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            Toast.makeText(requireActivity(), "Checking location permission", Toast.LENGTH_SHORT).show();
            if (ActivityCompat.checkSelfPermission(requireActivity(), android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(requireActivity(), android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
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

            executorService = Executors.newSingleThreadExecutor();

        }

        //uploadToFirebase(String.valueOf("Test"));
       /* try {

            socket = new Socket(serverAddress, serverPort);


            outputStream = socket.getOutputStream();
            out = new PrintWriter(outputStream, true);

            String messageToSend = "Hello, server!";
            out.println(messageToSend);

            out.close();
            outputStream.close();
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }*/

    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class));
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().registerReceiver(broadcastReceiver, new IntentFilter(Constants.INTENT_ACTION_GRANT_USB));
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
        if(controlLinesEnabled && controlLines != null && connected == Connected.True)
            controlLines.start();
    }

    @Override
    public void onPause() {
        getActivity().unregisterReceiver(broadcastReceiver);
        if(controlLines != null)
            controlLines.stop();
        super.onPause();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText));
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> startPeriodicOperations());
        controlLines = new ControlLines(view);
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
        menu.findItem(R.id.hex).setChecked(hexEnabled);
        menu.findItem(R.id.controlLines).setChecked(controlLinesEnabled);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else if (id == R.id.controlLines) {
            controlLinesEnabled = !controlLinesEnabled;
            item.setChecked(controlLinesEnabled);
            if (controlLinesEnabled) {
                controlLines.start();
            } else {
                controlLines.stop();
            }
            return true;
        } else if (id == R.id.sendBreak) {
            try {
                usbSerialPort.setBreak(true);
                Thread.sleep(100);
                status("send BREAK");
                usbSerialPort.setBreak(false);
            } catch (Exception e) {
                status("send BREAK failed: " + e.getMessage());
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void connect() {
        connect(null);
    }

    private void connect(Boolean permissionGranted) {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getDeviceId() == deviceId)
                device = v;
        if(device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if(driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if(driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if(driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.getDevice())) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(Constants.INTENT_ACTION_GRANT_USB), flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if(usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        connected = Connected.Pending;
        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), usbConnection, usbSerialPort);
            service.connect(socket);
            onSerialConnect();
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        controlLines.stop();
        service.disconnect();
        usbSerialPort = null;
    }

    private void startPeriodicOperations() {
        sendCommands();
      /*  hexEnabled =true;
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    //sendParametersWithDelay();
                    sendCommands();
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.e("PeriodicOperations", "Error in periodic operation: " + e.getMessage());
                }
            }
        }, 10, 200); // 10 seconds in milliseconds*/
    }

    private void sendCommands() {
       /* if (!initializationDone) {
            sendInitializationCommands();
            initializationDone = true;
        }*/

     //   sendIntervalCommands();
        startWritingHexFileviaBluetoothUpdated();
    }

    private void sendInitializationCommands() {
        String[] parameters = {
                "ATWS", "ATD", "ATZ", "ATE0", "ATS0", "ATAL", "ATTP5"
        };

        for (String parameter : parameters) {
            try {
                String request = parameter ;
                send(request);
                Thread.sleep(500); // Delay for 10 milliseconds
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void sendIntervalCommands() {

       /* String request="";
        String[] parameters = {
                "33", "42", "04", "05", "0B", "0C", "0D",
                "0F", "10", "21", "23", "2C","03"
        };
        try {
            if(request_index>12) {
                request_index = 0;
            }
            if(request_index >11)  {
                request = parameters[request_index];
            }
            else {
                request = "01" + parameters[request_index];
            }
            UDSresponse=0;
            send(request);
            while(UDSresponse != 1) {
                Thread.sleep(500); // Delay for 10 milliseconds
                if(UDSresponse==1) {
                    break;
                }
                Thread.sleep(500); // Delay for 10 milliseconds
                if(UDSresponse==2) {
                    send("ATWS");
                    Thread.sleep(500); // Delay for 10 milliseconds
                    send("ATE0");
                    Thread.sleep(500); // Delay for 10 milliseconds
                    send("ATTP5");
                    Thread.sleep(500); // Delay for 10 milliseconds
                }
                send(request);
            }
            request_index++;
            if(request_index==13)  {
                request_index=0;
                Thread.sleep(30); // Delay for 10 milliseconds
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }*/
    }


  /*  private void sendParametersWithDelay() {
        String[] parameters = {
                "31", "33", "42", "04", "05", "0B", "0C", "0D",
                "0F", "10", "11", "1F", "21", "23", "2C", "2D"
        };
        first_service=1;
        for (String parameter : parameters) {
            try {
                String request = "01" + parameter ;
                if(parameter=="2D") {
                    first_service=12;
                }
                send(request);
                Thread.sleep(30); // Delay for 10 milliseconds
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }*/

    public void startWritingHexFileviaBluetoothUpdated() {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {

                    //17-10-2023 Updated
                    BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("V400_48_WOPB_V2.hex")));
                    String line;

                    StringBuilder dataBuilder = new StringBuilder();
                    StringBuilder dataBuilderMain = new StringBuilder();
                    StringBuilder dataBuilderChunk = new StringBuilder();
                    int skippedLineCount = 0;
                    boolean incrementSequence = false;

                    int chunkSize = 192;


                    StringBuilder hexDataBuilder = new StringBuilder();
                    boolean firstSequence = false;

                    while ((line = reader.readLine()) != null /*&& !DataRequestManager.resumeBuffer*/) {
                        lineCount++;
                        //DataRequestManager.resumeBuffer = true;

                        if(lineCount ==12807 || lineCount ==105583 || lineCount ==113779) {
                            sequence = 0;
                        }

                        // Application_0
                        if (lineCount > lineNumber && lineCount < 12807 ) {   //1ST SEQ-----7820// 1ST APP --12808 //7692      //7805    //7685 to 7692(1)..224, 7699(2)  ,7804(17)
                            Log.e("datawrite_tlineCount", String.valueOf(lineCount));
                            // Log.e("datawrite_tlineNumber", String.valueOf(lineNumber));
                            if (line.length() == 75) {


                                String trimmedLine = line.substring(9, line.length() - 2);

                                dataBuilder.append(trimmedLine);  //64 (32*2)

                                dataBuilderMain.append(trimmedLine);



                                if(lastBlock && lineCount ==12807){  //12807-6=12802  // Application End
                                    Log.e("datawrite_tl_12802", String.valueOf(lineCount));
                                    // reset = true;
                                    lastBlock = false;   ///12806----1st , 12802--00...4 lines
                                    sequence = 0;
                                    // hexDataBuilder.append("00").append(dataBuilder);
                                    hexDataBuilder.append(dataBuilder);
                                    dataBuilder.setLength(0);
                                    dataBuilderMain.setLength(0);
                                }

                                int byteslength = dataBuilder.length()/2;   //224


                                if (dataBuilder.length() % (chunkSize*2) == 0) {   //224  //192
                                    //  if(byteslength % chunkSize == 0){
                                    Log.e("datawrite_tlen_224", String.valueOf(dataBuilder.length()));

                                    //new code
                                    //if(newSequence){
                                    if(sequence==255){
                                        sequence=0;
                                    }else  if(sequence<=254) {
                                        sequence++;
                                    }
                                    // sequence++;
                                    hexString = String.format("%02X", sequence);
                                    //hexDataBuilder.append("0136").append(hexString).append(dataBuilder);
                                    hexDataBuilder.append("36").append(hexString).append(dataBuilder);
                                    //newSequence = false;
                                    dataBuilder.setLength(0);
                                    dataBuilderMain.setLength(0);


                                    /********* 19-10-23(10:19) *****************/


                                    chinkslength = hexDataBuilder.length()/2;

                                    sendChunkToCharacteristic(hexDataBuilder.toString(), chinkslength);
                                    Log.e("datawrite_tlen_chunks", String.valueOf(hexDataBuilder.length()/2));
                                    hexDataBuilder.setLength(0);

                                    // Introduce a 100ms delay
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }


                            } else {
                                blockCount++;
                                Log.e("datawrite_tblock", String.valueOf(blockCount));
                                if (blockCount == 3 || blockCount == 49 || blockCount == 53 || blockCount == 77) {
                                    // sequence = 1;
                                    lastBlock = true;
                                }
                            }
                        }
                    }
                    reader.close();

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void sendChunkToCharacteristic(String chunk, int chunklength) {
        byte[] dataBytes = convertHexStringToByteArray(chunk);
        writeLoopToCharacteristic(dataBytes, chunklength);
    }

    private void writeLoopToCharacteristic(byte[] bytes, int chunklength) {
        if(chunklength>0) {


            Log.e("DataWrite_tchunkWrite", String.valueOf(chunklength));

          /*  if (chunklength == 195) {
                chunkSize = 195;
            } else {
                chunkSize = 193;
            }*/
            //  chunkSize = 193;
            int chunkSize = chunklength;

            final Runnable sendChunkRunnable = new Runnable() {
                int dataIndex = 0;

                @Override
                public void run() {
                    if (dataIndex < bytes.length) {
                        int length = Math.min(chunkSize, bytes.length - dataIndex);
                        byte[] chunk = Arrays.copyOfRange(bytes, dataIndex, dataIndex + length);


                        if(chunk!=null) {
                            try {
                                service.write(chunk);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        dataIndex += length;


                        handler.postDelayed(this, 90);
                    }
                }
            };

            handler.post(sendChunkRunnable);



        }
    }

    private byte[] convertHexStringToByteArray(String hexString) {
        // Converting the hex string to a byte array
        int len = hexString.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                    + Character.digit(hexString.charAt(i + 1), 16));
        }
        return data;
    }

    private void send(String str) {
      /*  if(connected != Connected.True) {
            //Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            Charset charset= StandardCharsets.US_ASCII;
            if(hexEnabled) {
                StringBuilder sb = new StringBuilder();
                TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                TextUtil.toHexString(sb, newline.getBytes());
                //msg = sb.toString();
                //data = TextUtil.fromHexString(msg);
                reqstr=str;
                msg = str+"\r";
                //data = (str + newline).getBytes();
                data = msg.getBytes(charset);
            } else {
                msg = str;
                //data = (str + newline).getBytes();
                data = msg.getBytes(charset);
            }
            requireActivity().runOnUiThread(() -> {
                SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
                spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                //receiveText.append(spn);
                //uploadToFirebase("Request : "+String.valueOf(spn));
            });
            service.write(data);  // write data to USB serial CAN device
        } catch (SerialTimeoutException e) {
            status("write timeout: " + e.getMessage());
        } catch (Exception e) {
            onSerialIoError(e);
        }*/
    }

    private void uploadToFirebase(String data) {
        DatabaseReference newChildRef = firebaseDatabaseRef.push();
        newChildRef.setValue(data);
        Log.e( "uploaded to Firebase",data);
    }

   /* private void receive(ArrayDeque<byte[]> datas) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        for (byte[] data : datas) {
            if (hexEnabled) {
                spn.append(TextUtil.toHexString(data)).append('\n');
            } else {
                String msg = new String(data);
                if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {

                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);

                    if (pendingNewline && msg.charAt(0) == '\n') {
                        if(spn.length() >= 2) {
                            spn.delete(spn.length() - 2, spn.length());
                        } else {
                            Editable edt = receiveText.getEditableText();
                            if (edt != null && edt.length() >= 2)
                                edt.delete(edt.length() - 2, edt.length());
                        }
                    }
                    pendingNewline = msg.charAt(msg.length() - 1) == '\r';
                }
                spn.append(TextUtil.toCaretString(msg, newline.length() != 0));
            }
        }

        //System.out.println("Hexadecimal Bytes: " + hexBytes.toString());
        uploadToFirebase("Response : " + spn);           // Output: 04 62 10 1A
        receiveText.append("Response : " + spn);
        sendMessageToServer(serverAddress,serverPort, String.valueOf(spn));
    }*/
  /* private void receive(ArrayDeque<byte[]> datas) {
       requireActivity().runOnUiThread(() -> {

           SpannableStringBuilder spn = new SpannableStringBuilder();
           for (byte[] data : datas) {
               if (hexEnabled) {
                   spn.append(TextUtil.toHexString(data)).append('\n');

               } else {
                   String msg = new String(data);
                   if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {

                       if (pendingNewline && msg.charAt(0) == '\n') {
                           if (spn.length() >= 2) {
                               spn.delete(spn.length() - 2, spn.length());
                           } else {
                               Editable edt = receiveText.getEditableText();
                               if (edt != null && edt.length() >= 2) {
                                   edt.delete(edt.length() - 2, edt.length());
                               }
                           }
                       }
                       pendingNewline = msg.charAt(msg.length() - 1) == '\r';
                   }
                   spn.append(TextUtil.toCaretString(msg, newline.length() != 0));

               }
           }
           uploadToFirebase("Response : " + spn.toString());

           receiveText.append("Response : ");
           receiveText.append(spn);

           sendMessageToServer(serverAddress, serverPort, spn.toString());
       });
   }*/

    /* private void receive(ArrayDeque<byte[]> datas) {
         requireActivity().runOnUiThread(() -> {

             SpannableStringBuilder spn = new SpannableStringBuilder();
             for (byte[] data : datas) {
                 if (hexEnabled) {
                     spn.append(TextUtil.toHexString(data)).append('\n');

                 } else {
                     String msg = new String(data);
                     if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                         if (pendingNewline && msg.charAt(0) == '\n') {
                             int charactersToDelete = 2;
                             if (spn.length() >= charactersToDelete) {
                                 spn.delete(spn.length() - charactersToDelete, spn.length());
                             } else {
                                 Editable edt = receiveText.getEditableText();
                                 int edtLength = edt != null ? edt.length() : 0;
                                 if (edtLength >= charactersToDelete) {
                                     edt.delete(edtLength - charactersToDelete, edtLength);
                                 }
                             }
                         }
                         pendingNewline = msg.charAt(msg.length() - 1) == '\r';
                     }
                     spn.append(TextUtil.toCaretString(msg, newline.length() != 0));

                 }
             }

             uploadToFirebase("Response : " + spn.toString());

             receiveText.append("Response : ");
             receiveText.append(spn);

             sendMessageToServer(serverAddress, serverPort, spn.toString());
         });
     }*/
    private void receive(ArrayDeque<byte[]> datas) {
        requireActivity().runOnUiThread(() -> {

            //SpannableStringBuilder spn = new SpannableStringBuilder();
            String str="";
            String req_res="";


            Charset charset= StandardCharsets.US_ASCII;
            for (byte[] data : datas) {
                if (hexEnabled) {
                    // spn.append(TextUtil.toHexString(data)).append('\n');

                    str=new String(data,charset);



                } else {
                    String msg = new String(data);
                    int msgLength = msg.length();

                    if (newline.equals(TextUtil.newline_crlf) && msgLength > 0) {
                        if (pendingNewline && msg.charAt(0) == '\n') {
                            int charactersToDelete = 2;
                            if (spn.length() >= charactersToDelete) {
                                //   spn.delete(spn.length() - charactersToDelete, spn.length());
                            } else {
                                Editable edt = receiveText.getEditableText();
                                int edtLength = edt != null ? edt.length() : 0;
                                if (edtLength >= charactersToDelete) {
                                    edt.delete(edtLength - charactersToDelete, edtLength);
                                }
                            }
                        }
                        pendingNewline = msg.charAt(msgLength - 1) == '\r';
                    }

                    if (!pendingNewline) {
                        // spn.append(TextUtil.toCaretString(msg, newline.length() != 0));
                    }
                }
            }
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMddHHmmss");
            Date currentDate = new Date();

            String dateTime = dateFormat.format(currentDate);
            long unixTime = convertToUnixTime(dateTime);

            Log.d("Formatted Date Time: ", dateTime);
            Log.d("Unix Time: ", String.valueOf(unixTime));

           /* SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMddHHmmss", Locale.getDefault());
            String dateTime = dateFormat.format(new Date());*/

          /*  long epoch;
            try {
                epoch = new java.text.SimpleDateFormat("MM/dd/yyyy HH:mm:ss").parse("01/01/1970 01:00:00").getTime() / 1000;
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
            String unixTime =String.valueOf(epoch);*/

            spn= spn+str ;
            if(str.contains("\r")) {

                receive_complete=1;
                Log.e("Response_data", String.valueOf(spn));
            }


            //spn_new.append(spn);
            //count++;
            // Log.e("count", String.valueOf(count));
            // Log.e("Response_spn_logs", String.valueOf(spn_new));
            if(receive_complete==1) {
                receive_complete=0;
                if (spn.contains("BUS INIT: ERROR") || spn.contains("DATA ERROR") || spn.contains("STOPPED")) {
                    UDSresponse=2;
                } else if (spn.contains("NO")  || spn.contains("BUS ERROR") ) {
                    // Log.e("Response_data", String.valueOf(spn));
                    UDSresponse=2;
                    //sendCommands();
                } else {
                    // Log.e("Response_data", String.valueOf(spn));
                    if (spn.contains("41") || spn.contains("7F") || spn.contains("43") || spn.contains("47") ) {
                        UDSresponse = 1;
                        spn= spn.substring(0,spn.length()-1);
                        spn = spn.replaceAll(">", "");
                        spn= spn.replaceAll("\\s+", "");
                        if(spn.length() < 16) {
                            while (spn.length() < 16) {
                                spn= spn+"0";
                            }
                            // spn= spn.padEnd(16-spn.length(),"0");
                        }
                        if ((request_index == 0 )|| (request_index==12)) {
                            // first_service = 0;
                            data_new = "";   //clear the string
                            String Package_Header = "$$CLIENT_1NS,862843041050881,1," + lat + "," + lon + "," + unixTime + "," + gnssFixStatusSt + "," + gsmSignalStrengthSt + "," + speedSt + ",583,3," + satellite + "," + hdopSt + ",0,0,12181,2050,12181,3960,10023,21,";

                            req_res = "|" + reqstr + ":" + spn;
                            data_new = Package_Header + req_res;
                        } else {
                            req_res = "|" + reqstr + ":" + spn;
                            data_new = data_new + req_res;
                        }

                        if ((request_index == 11) || (request_index == 12)) {

                            data_new = data_new + "|*66";

                            Log.e("Response__data_length", String.valueOf(spn_new.length()));


                            receiveText.setText(data_new);

                            // Sending response to the Client server
                            uploadToFirebase("Response : " + data_new);

                            sendMessageToServer(serverAddress, serverPort, data_new);
                            //     Log.e("Response_data", String.valueOf(data_new));
                            //receiveText.setText(spn_new);

                            //responses.clear();
                            spn_new.clear();


                        }
                    }
                }
                spn="";
            }

        });
    }

    private long convertToUnixTime(String dateTime) {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMddHHmmss");
            Date date = dateFormat.parse(dateTime);
            long unixTime = date.getTime() / 1000;
            return unixTime;
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }


    void status(String str) {
        requireActivity().runOnUiThread(() -> {
            SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            uploadToFirebase("Status : " + String.valueOf(spn));
        });
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
        if(controlLinesEnabled)
            controlLines.start();
        //startPeriodicOperations();
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
        //receiveText.append(spn);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

    class ControlLines {
        private static final int refreshInterval = 200; // msec

        private final Handler mainLooper;
        private final Runnable runnable;
        private final LinearLayout frame;
        private final ToggleButton rtsBtn, ctsBtn, dtrBtn, dsrBtn, cdBtn, riBtn;

        ControlLines(View view) {
            mainLooper = new Handler(Looper.getMainLooper());
            runnable = this::run;

            frame = view.findViewById(R.id.controlLines);
            rtsBtn = view.findViewById(R.id.controlLineRts);
            ctsBtn = view.findViewById(R.id.controlLineCts);
            dtrBtn = view.findViewById(R.id.controlLineDtr);
            dsrBtn = view.findViewById(R.id.controlLineDsr);
            cdBtn = view.findViewById(R.id.controlLineCd);
            riBtn = view.findViewById(R.id.controlLineRi);
            rtsBtn.setOnClickListener(this::toggle);
            dtrBtn.setOnClickListener(this::toggle);
        }

        private void toggle(View v) {
            ToggleButton btn = (ToggleButton) v;
            if (connected != Connected.True) {
                btn.setChecked(!btn.isChecked());
                //Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            String ctrl = "";
            try {
                if (btn.equals(rtsBtn)) { ctrl = "RTS"; usbSerialPort.setRTS(btn.isChecked()); }
                if (btn.equals(dtrBtn)) { ctrl = "DTR"; usbSerialPort.setDTR(btn.isChecked()); }
            } catch (IOException e) {
                status("set" + ctrl + " failed: " + e.getMessage());
            }
        }

        private void run() {
            if (connected != Connected.True)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getControlLines();
                rtsBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.RTS));
                ctsBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.CTS));
                dtrBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.DTR));
                dsrBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.DSR));
                cdBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.CD));
                riBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.RI));
                mainLooper.postDelayed(runnable, refreshInterval);
            } catch (IOException e) {
                status("getControlLines() failed: " + e.getMessage() + " -> stopped control line refresh");
            }
        }

        void start() {
            frame.setVisibility(View.VISIBLE);
            if (connected != Connected.True)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getSupportedControlLines();
                if (!controlLines.contains(UsbSerialPort.ControlLine.RTS)) rtsBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.CTS)) ctsBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.DTR)) dtrBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.DSR)) dsrBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.CD))   cdBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.RI))   riBtn.setVisibility(View.INVISIBLE);
                run();
            } catch (IOException e) {
                // Toast.makeText(getActivity(), "getSupportedControlLines() failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        void stop() {
            frame.setVisibility(View.GONE);
            mainLooper.removeCallbacks(runnable);
            rtsBtn.setChecked(false);
            ctsBtn.setChecked(false);
            dtrBtn.setChecked(false);
            dsrBtn.setChecked(false);
            cdBtn.setChecked(false);
            riBtn.setChecked(false);
        }
    }

    public void sendMessageToServer(String serverAddress, int serverPort, final String message) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {

                    Socket socket = new Socket(serverAddress, serverPort);

                    OutputStream outputStream = socket.getOutputStream();
                    PrintWriter out = new PrintWriter(outputStream, true);


                    out.println(message);

                    try {
                        out.close();
                        outputStream.close();
                        socket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                        Log.e("SocketException", "Error while closing socket: " + e.getMessage());
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void requestLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(requireActivity(), android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(requireActivity(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
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
                TelephonyManager telephonyManager = (TelephonyManager) requireActivity().getSystemService(Context.TELEPHONY_SERVICE);
                int gsmSignalStrength = 0; // Signal strength in dBm
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    gsmSignalStrength = telephonyManager.getSignalStrength().getGsmSignalStrength();
                }


                SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMddHHmmss", Locale.getDefault());
                String dateTime = dateFormat.format(new Date());

                lat = latitude;
                lon = longitude;
                speedSt = speed;
                bearingSt = bearing;
                satellite = satellitesUsed;
                hdopSt = hdop;
                gnssFixStatusSt = gnssFixStatus;
                gsmSignalStrengthSt = gsmSignalStrength;
                dateTimeSt = dateTime;



                String deviceInfo = String.format("LAT=%s,LON=%s,DATETIME=%s,GNSS=%s,GSM_SIGNAL=%ddBm,SPEED=%.2f m/s,BEARING=%.2f°,SATELLITES_USED=%d,HDOP=%.2f",latitude, longitude, dateTime, gnssFixStatus, gsmSignalStrength, speed, bearing, satellitesUsed, hdop);

                uploadToFirebase(deviceInfo);
                Log.e("DeviceInfoUpload: ",deviceInfo);
                Toast.makeText(requireActivity(), "GPS coordinates uploaded to Cloud", Toast.LENGTH_SHORT).show();
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
