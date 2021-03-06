package com.example.SDXbeta.activities;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import com.example.SDXbeta.AuthServer;
import com.example.SDXbeta.PacketHelper;
import com.example.SDXbeta.R;
import com.example.SDXbeta.SimpleCrypto;
import com.example.xbee_i2r.*;
import com.ftdi.j2xx.FT_Device;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TFARequest extends Activity {
    EditText editTextNodeId;
    String username;
    String password;
    Toast toast;
    private FT_Device ftDev;
    private BroadcastReceiver receiver;
    private String authKey;
    private String xbeeNodeId;
    private String deviceId;
    private String nonceNode;
    private String nonce;
    private DefaultHttpClient httpClient;
    int[] responseData;

    protected void onStart() {
        super.onStart();

        IntentFilter filter = new IntentFilter(ReadService.TFA_REQUEST_ACTION);

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                responseData = intent.getIntArrayExtra("responseData");

                // Convert integers to bytes
                byte[] byteResponseData = new byte[responseData.length];
                for (int i = 0; i < byteResponseData.length; i++) {
                    byteResponseData[i] = (byte) responseData[i];
                }

                // Decrypt the data
                try {
                    byteResponseData = SimpleCrypto.decrypt(authKey, byteResponseData);

                    // Get Fio nonce
                    byte[] hexFioNonce = { byteResponseData[10], byteResponseData[11] };
                    nonceNode = SimpleCrypto.toHex(hexFioNonce);

                    // Get timestamp
                    byte[] hexTimestamp = { byteResponseData[14], byteResponseData[15], byteResponseData[16], byteResponseData[17] };

                    // Check if the packet is valid (node ID, device ID, nonce)
                    if (PacketHelper.isValidPacket(byteResponseData, xbeeNodeId, deviceId, nonce)) {
                        toast = Toast.makeText(getBaseContext(), "Verified by node, requesting server for 2FA key", Toast.LENGTH_LONG);

                        // Request the server for a 2FA token
                        new RequestTokenTask().execute();
                    }

                    else {
                        toast = Toast.makeText(getBaseContext(), "Node verification failed", Toast.LENGTH_SHORT);
                    }

                    toast.show();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };

        registerReceiver(receiver,filter);
    }

    @Override
    protected void onStop(){
        super.onStop();
        unregisterReceiver(receiver);
    }


    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tfa_request);

        // Get username/password from intent (sent by Login activity)
        username = getIntent().getStringExtra("username");
        password = getIntent().getStringExtra("password");

        // Create an HttpClient
        httpClient = new AuthServer().getNewHttpClient(username, password);

        // Get hex string of device identifier
        deviceId = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);

        ftDev = InitializeDevice.getDevice();
    }

    public void onRequestKeyClicked(View view) {
        editTextNodeId = (EditText) findViewById(R.id.editTextNodeId);
        xbeeNodeId = editTextNodeId.getText().toString();
        new RequestKeyTask().execute(xbeeNodeId);
    }

    // Send an encrypted packet to the node requesting permission for 2FA
    // After the request is sent, the node should send a response to the mobile device; this is handled by the
    // BroadcastReceiver created in onStart
    public void onRequest2FAClicked(View view) {

        // Create a 4-digit hex nonce string
        Random random = new Random();
        nonce = Integer.toString(random.nextInt((9999 - 1000) + 1) + 1000);

        Log.d("NONCE", nonce);

        try {
            byte[] result = PacketHelper.create2FARequestPacket(nonce, deviceId, xbeeNodeId, authKey);

            XBeeAddress16 destination = new XBeeAddress16(0xFF, 0xFF);

            TxRequest16 request = new TxRequest16(destination, PacketHelper.createPayload(result));
            XBeePacket packet = new XBeePacket(request.getFrameData());

            byte[] outData = PacketHelper.createOutData(packet);

            synchronized(ftDev){
                if (ftDev.isOpen() == false) {
                    return;
                }

                ftDev.write(outData, outData.length);
            }
        }

        catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Requests the server for the AES encryption key of the specified node
    private class RequestKeyTask extends AsyncTask<String, Void, String> {

        protected String doInBackground(String... nodeIdList) {
            HttpGet httpGet = new HttpGet(AuthServer.SERVER_URL + "keys/" + nodeIdList[0]);

            try {
                HttpResponse response = httpClient.execute(httpGet);
                Integer statusCode = response.getStatusLine().getStatusCode();

                // Success
                if (statusCode == 200) {
                    // Get response content
                    String line;
                    StringBuilder stringBuilder = new StringBuilder();
                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));

                    while ((line = bufferedReader.readLine()) != null) {
                        stringBuilder.append(line);
                    }

                    JSONObject nodeObject = new JSONObject(stringBuilder.toString());

                    authKey = nodeObject.getString("authKey");
                    return authKey;
                }
            }
            catch (ClientProtocolException e) {
                e.printStackTrace();
            }
            catch (IOException e) {
                e.printStackTrace();
            } catch (JSONException e) {
                e.printStackTrace();
            }

            return null;
        }

        protected void onPostExecute(String authKey) {
            if (authKey == null) {
                toast = Toast.makeText(getBaseContext(), "An error occurred", Toast.LENGTH_SHORT);
            }

            else {
                toast = Toast.makeText(getBaseContext(), "Key: " + authKey, Toast.LENGTH_SHORT);

                // Enable the request 2FA button
                // User should click this triggering the onRequest2FAClicked handler
                findViewById(R.id.requestNode2FA).setEnabled(true);
            }

            toast.show();
        }
    }

    // Request server for 2FA token
    // The response is received after the email is sent
    private class RequestTokenTask extends AsyncTask<String, Void, Boolean> {

        protected Boolean doInBackground(String... urls) {
            // Make post request
            HttpPost httpPost = new HttpPost(AuthServer.SERVER_URL + "token-requests");

            List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>();
            nameValuePairs.add(new BasicNameValuePair("nodeId", xbeeNodeId));
            nameValuePairs.add(new BasicNameValuePair("deviceId", deviceId));

            try {
                httpPost.setEntity(new UrlEncodedFormEntity(nameValuePairs));
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            try {
                HttpResponse response = httpClient.execute(httpPost);
                Integer statusCode = response.getStatusLine().getStatusCode();

                if (statusCode == 200) {
                    // Get response content
                    String line;
                    StringBuilder stringBuilder = new StringBuilder();
                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));

                    while ((line = bufferedReader.readLine()) != null) {
                        stringBuilder.append(line);
                    }

                    return true;
                }
            }
            catch (ClientProtocolException e) {
                e.printStackTrace();
            }
            catch (IOException e) {
                e.printStackTrace();
            }

            return false;
        }

        protected void onPostExecute(Boolean success) {
            if (success) {
                toast = Toast.makeText(getBaseContext(), "2FA token created and sent", Toast.LENGTH_SHORT);
                toast.show();

                // Start new activity, passing it the details
                Intent intent = new Intent(getBaseContext(), TFAToken.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                intent.putExtra("authKey", authKey);
                intent.putExtra("deviceId", deviceId);
                intent.putExtra("nodeId", xbeeNodeId);
                intent.putExtra("nonceSelf", nonce);
                intent.putExtra("nonceNode", nonceNode);

                startActivity(intent);
            }

            else {
                toast = Toast.makeText(getBaseContext(), "An error occurred", Toast.LENGTH_SHORT);
                toast.show();
            }
        }
    }
}