package com.zaext.nicehckcontroller; // 确保这里的包名和你自己的一致！

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.ArrayList;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "NiceHCK_Control";
    private static final int PERMISSION_REQUEST_CODE = 101;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothController bluetoothController; // 使用单例控制器

    private Spinner spinnerDevices;
    private TextView textStatus;
    private TextView textBattery;
    private TextView textAncMode;
    private Button btnRefreshStatus;

    private ArrayList<BluetoothDevice> pairedDevicesList = new ArrayList<>();
    private ArrayList<String> pairedDeviceNames = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化 UI 控件
        spinnerDevices = findViewById(R.id.spinner_devices);
        textStatus = findViewById(R.id.text_status);
        textBattery = findViewById(R.id.text_battery);
        textAncMode = findViewById(R.id.text_anc_mode);
        btnRefreshStatus = findViewById(R.id.btn_refresh_status);

        // 初始化蓝牙控制器单例
        bluetoothController = BluetoothController.getInstance(this);

        // 设置状态监听器
        setupStateListener();

        // 设置刷新按钮点击事件
        btnRefreshStatus.setOnClickListener(v -> refreshDeviceStatus());

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "该设备不支持蓝牙", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        checkAndRequestPermissions();

        // TODO 自动连接默认设备
    }

    // --- 权限处理 ---
    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, PERMISSION_REQUEST_CODE);
            } else {
                listPairedDevices();
            }
        } else {
            listPairedDevices();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                listPairedDevices();
            } else {
                Toast.makeText(this, "需要蓝牙权限才能使用！", Toast.LENGTH_LONG).show();
            }
        }
    }

    // --- 查找已配对设备 ---
    private void listPairedDevices() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return; // 权限检查
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        pairedDevicesList.clear();
        pairedDeviceNames.clear();

        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                pairedDevicesList.add(device);
                pairedDeviceNames.add(device.getName());
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, pairedDeviceNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevices.setAdapter(adapter);
    }

    // --- 连接逻辑 (已简化，全部委托给 Controller) ---
    public void connectDevice(View view) {
        // MainActivity 不再处理连接细节，只负责触发
        new Thread(() -> {
            boolean isConnected = bluetoothController.connectDefaultDevice();
            runOnUiThread(() -> {
                if (isConnected) {
                    textStatus.setText("状态：已连接");
                    textStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));

                    // 连接成功后自动查询状态
                    refreshDeviceStatus();
                } else {
                    textStatus.setText("状态：连接失败");
                }
            });
        }).start();
    }

    // ==========================================
    // ============ 状态管理 ===================
    // ==========================================

    /**
     * 设置状态监听器
     */
    private void setupStateListener() {
        bluetoothController.setStateListener(new BluetoothController.StateListener() {
            @Override
            public void onBatteryChanged(int left, int right) {
                runOnUiThread(() -> updateBatteryUI(left, right));
            }

            @Override
            public void onAncModeChanged(int mode) {
                runOnUiThread(() -> updateAncModeUI(mode));
            }
        });
    }

    /**
     * 刷新设备状态（查询电量和ANC模式）
     */
    private void refreshDeviceStatus() {
        if (!bluetoothController.isConnected()) {
            Toast.makeText(this, "设备未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        // 查询电量
        bluetoothController.queryBattery();

        // 延迟100ms后查询ANC模式，避免指令冲突
        new android.os.Handler().postDelayed(() -> {
            bluetoothController.queryAncMode();
        }, 100);
    }

    /**
     * 更新电量显示
     */
    private void updateBatteryUI(int left, int right) {
        if (left >= 0 && right >= 0) {
            textBattery.setText("电量: 左 " + left + "% | 右 " + right + "%");
            textBattery.setTextColor(getResources().getColor(android.R.color.black));
        } else {
            textBattery.setText("电量: 未知");
            textBattery.setTextColor(getResources().getColor(android.R.color.darker_gray));
        }
    }

    /**
     * 更新ANC模式显示
     */
    private void updateAncModeUI(int mode) {
        String modeName = bluetoothController.getAncModeName();
        if (mode >= 0) {
            textAncMode.setText("ANC模式: " + modeName);
            textAncMode.setTextColor(getResources().getColor(android.R.color.black));
        } else {
            textAncMode.setText("ANC模式: 未知");
            textAncMode.setTextColor(getResources().getColor(android.R.color.darker_gray));
        }
    }

    // ==========================================
    // ============ 功能指令区 (调用 Controller) =======
    // ==========================================

    private void sendCommand(byte[] packet) {
        if (!bluetoothController.isConnected()) {
            Toast.makeText(this, "耳机未连接，请先连接", Toast.LENGTH_SHORT).show();
            return;
        }
        bluetoothController.sendRaw(packet);
        Toast.makeText(this, "指令已发送", Toast.LENGTH_SHORT).show();
    }

    // --- 降噪控制 ---
    public void setAncOff(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x00, 0x00});
        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
    }
    public void setAncTransparency(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x01, 0x00});
        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
    }
    public void setAncNormal(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x02, 0x00});
        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
    }
    public void setAncDeep(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x03, 0x00});
        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
    }
    public void setAncExperimental(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x10, 0x00});
        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
    }
    public void setAncWind(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x11, 0x00});
        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
    }

    // --- EQ 控制 ---
    public void setEqBlue(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x07, 0x02, 0x00});
    }
    public void setEqBalanced(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x07, 0x02, 0x01});
    }
    public void setEqBass(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x07, 0x02, 0x02});
    }
    public void setEqPure(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x07, 0x02, 0x03});
    }
    public void setEqGame(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x07, 0x02, 0x04});
    }

    // --- 高级功能 ---
    public void setGameModeOn(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x08, 0x02, 0x01});
    }
    public void setGameModeOff(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x08, 0x02, 0x00});
    }
    public void setLowLatencyOn(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x06, 0x02, 0x01});
    }
    public void setLowLatencyOff(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x06, 0x02, 0x00});
    }
    public void setDualConnOn(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x05, 0x02, 0x01});
    }
    public void setDualConnOff(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x05, 0x02, 0x00});
    }
    public void setCodecLHDC(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x04, 0x02, 0x01});
    }
    public void setCodecAAC(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x04, 0x02, 0x00});
    }
    public void setAntiWindOn(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, (byte)0xE1, 0x02, 0x01});
    }
    public void setAntiWindOff(View v) {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, (byte)0xE1, 0x02, 0x00});
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 当 App 退出时，可以选择断开连接
        // 如果想让磁贴在后台也能用，可以把这行注释掉
        if (bluetoothController != null) {
            bluetoothController.close();
        }
    }
}