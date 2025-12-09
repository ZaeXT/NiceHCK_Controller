package com.zaext.nicehckcontroller;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
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
    private BluetoothController bluetoothController;

    private Spinner spinnerDevices;
    private Spinner spinnerAnc;
    private Spinner spinnerEq;
    private Spinner spinnerGameMode;
    private Spinner spinnerLowLatency;
    private Spinner spinnerDualConn;
    private Spinner spinnerCodec;
    private Spinner spinnerAntiWind;

    private TextView textStatus;
    private TextView textBattery;
    private TextView textAncMode;
    private Button btnRefreshStatus;

    private ArrayList<BluetoothDevice> pairedDevicesList = new ArrayList<>();
    private ArrayList<String> pairedDeviceNames = new ArrayList<>();

    private boolean isInitializing = true; // 防止初始化时触发选择事件
    private boolean isSyncingFromDevice = false; // 防止设备状态同步时触发命令
    private AdapterView.OnItemSelectedListener ancSpinnerListener; // 保存降噪 Spinner 监听器

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化蓝牙控制器单例
        bluetoothController = BluetoothController.getInstance(this);

        // 设置状态监听器
        setupStateListener();

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "该设备不支持蓝牙", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 初始化 UI 控件
        initializeViews();

        // 设置所有 Spinner
        setupSpinners();

        // 初始状态下禁用所有控制 Spinner
        enableControlSpinners(false);

        checkAndRequestPermissions();

        // 自动连接设备
        new Thread(() -> {
            boolean isConnected = bluetoothController.connectDefaultDevice();
            runOnUiThread(() -> {
                // 连接完成后才允许触发 Spinner 事件
                isInitializing = false;
                updateConnectionStatus(isConnected);
                if (isConnected) {
                    refreshDeviceStatus();
                }
            });
        }).start();
    }

    private void initializeViews() {
        spinnerDevices = findViewById(R.id.spinner_devices);
        spinnerAnc = findViewById(R.id.spinner_anc);
        spinnerEq = findViewById(R.id.spinner_eq);
        spinnerGameMode = findViewById(R.id.spinner_game_mode);
        spinnerLowLatency = findViewById(R.id.spinner_low_latency);
        spinnerDualConn = findViewById(R.id.spinner_dual_conn);
        spinnerCodec = findViewById(R.id.spinner_codec);
        spinnerAntiWind = findViewById(R.id.spinner_anti_wind);

        textStatus = findViewById(R.id.text_status);
        textBattery = findViewById(R.id.text_battery);
        textAncMode = findViewById(R.id.text_anc_mode);
        btnRefreshStatus = findViewById(R.id.btn_refresh_status);

        btnRefreshStatus.setOnClickListener(v -> refreshDeviceStatus());
    }

    private void setupSpinners() {
        // 降噪模式
        String[] ancModes = {"关闭", "通透", "普通", "深度", "实验", "抗风噪"};
        setupSpinner(spinnerAnc, ancModes);

        // 创建并保存降噪 Spinner 监听器
        ancSpinnerListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isInitializing && !isSyncingFromDevice) {
                    switch (position) {
                        case 0: setAncOff(); break;
                        case 1: setAncTransparency(); break;
                        case 2: setAncNormal(); break;
                        case 3: setAncDeep(); break;
                        case 4: setAncExperimental(); break;
                        case 5: setAncWind(); break;
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        spinnerAnc.setOnItemSelectedListener(ancSpinnerListener);

        // EQ 音效
        String[] eqModes = {"悔恨之泪", "均衡中正", "欧美澎湃", "真律还原", "游戏优化"};
        setupSpinner(spinnerEq, eqModes);
        spinnerEq.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isInitializing) {
                    switch (position) {
                        case 0: setEqBlue(); break;
                        case 1: setEqBalanced(); break;
                        case 2: setEqBass(); break;
                        case 3: setEqPure(); break;
                        case 4: setEqGame(); break;
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // 游戏模式
        String[] onOffOptions = {"关", "开"};
        setupSpinner(spinnerGameMode, onOffOptions);
        spinnerGameMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isInitializing) {
                    if (position == 0) setGameModeOff();
                    else setGameModeOn();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // 低延迟
        setupSpinner(spinnerLowLatency, onOffOptions);
        spinnerLowLatency.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isInitializing) {
                    if (position == 0) setLowLatencyOff();
                    else setLowLatencyOn();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // 双设备连接
        setupSpinner(spinnerDualConn, onOffOptions);
        spinnerDualConn.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isInitializing) {
                    if (position == 0) setDualConnOff();
                    else setDualConnOn();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // 音频编码
        String[] codecOptions = {"AAC", "LHDC"};
        setupSpinner(spinnerCodec, codecOptions);
        spinnerCodec.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isInitializing) {
                    if (position == 0) setCodecAAC();
                    else setCodecLHDC();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // 抗风噪
        setupSpinner(spinnerAntiWind, onOffOptions);
        spinnerAntiWind.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isInitializing) {
                    if (position == 0) setAntiWindOff();
                    else setAntiWindOn();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupSpinner(Spinner spinner, String[] options) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.spinner_item, options);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void updateConnectionStatus(boolean connected) {
        if (textStatus != null) {
            if (connected) {
                textStatus.setText("状态：✅ 已连接");
                textStatus.setTextColor(getResources().getColor(R.color.status_connected));
            } else {
                textStatus.setText("状态：❌ 连接失败");
                textStatus.setTextColor(getResources().getColor(R.color.status_disconnected));
            }
        }

        // 根据连接状态启用或禁用控制 Spinner
        enableControlSpinners(connected);
    }

    private void enableControlSpinners(boolean enabled) {
        if (spinnerAnc != null) spinnerAnc.setEnabled(enabled);
        if (spinnerEq != null) spinnerEq.setEnabled(enabled);
        if (spinnerGameMode != null) spinnerGameMode.setEnabled(enabled);
        if (spinnerLowLatency != null) spinnerLowLatency.setEnabled(enabled);
        if (spinnerDualConn != null) spinnerDualConn.setEnabled(enabled);
        if (spinnerCodec != null) spinnerCodec.setEnabled(enabled);
        if (spinnerAntiWind != null) spinnerAntiWind.setEnabled(enabled);
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
            return;
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        pairedDevicesList.clear();
        pairedDeviceNames.clear();

        if (!pairedDevices.isEmpty()) {
            for (BluetoothDevice device : pairedDevices) {
                pairedDevicesList.add(device);
                pairedDeviceNames.add(device.getName());
            }
        }

        if (spinnerDevices != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, pairedDeviceNames);
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerDevices.setAdapter(adapter);
        }
    }

    // --- 连接设备 ---
    public void connectDevice(View view) {
        new Thread(() -> {
            boolean isConnected = bluetoothController.connectDefaultDevice();
            runOnUiThread(() -> {
                updateConnectionStatus(isConnected);
                if (isConnected) {
                    refreshDeviceStatus();
                }
            });
        }).start();
    }

    // ==========================================
    // ============ 状态管理 ===================
    // ==========================================

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

    private void refreshDeviceStatus() {
        if (!bluetoothController.isConnected()) {
            Toast.makeText(this, "设备未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        bluetoothController.queryBattery();
        new android.os.Handler().postDelayed(() -> {
            bluetoothController.queryAncMode();
        }, 100);
    }

    private void updateBatteryUI(int left, int right) {
        if (textBattery != null) {
            if (left >= 0 && right >= 0) {
                textBattery.setText("🔋 电量: 左 " + left + "% | 右 " + right + "%");
                textBattery.setTextColor(getResources().getColor(R.color.text_primary));
            } else {
                textBattery.setText("🔋 电量: 未知");
                textBattery.setTextColor(getResources().getColor(R.color.text_secondary));
            }
        }
    }

    private void updateAncModeUI(int mode) {
        if (textAncMode != null) {
            String modeName = bluetoothController.getAncModeName();
            if (mode >= 0) {
                textAncMode.setText("🎧 ANC模式: " + modeName);
                textAncMode.setTextColor(getResources().getColor(R.color.text_primary));
            } else {
                textAncMode.setText("🎧 ANC模式: 未知");
                textAncMode.setTextColor(getResources().getColor(R.color.text_secondary));
            }
        }

        // 同步更新降噪模式 Spinner
        if (spinnerAnc != null && mode >= 0) {
            int position = ancModeToSpinnerPosition(mode);
            if (position >= 0 && spinnerAnc.getSelectedItemPosition() != position) {
                // 设置标志，防止触发命令发送
                isSyncingFromDevice = true;
                spinnerAnc.setSelection(position);
                // 使用 Handler 延迟清除标志，确保所有事件处理完成
                new android.os.Handler().postDelayed(() -> {
                    isSyncingFromDevice = false;
                }, 200);
            }
        }
    }

    /**
     * 将 ANC 模式值转换为 Spinner 位置
     * @param mode ANC模式值 (0x00, 0x01, 0x02, 0x03, 0x10, 0x11)
     * @return Spinner位置 (0-5)，如果无法识别返回 -1
     */
    private int ancModeToSpinnerPosition(int mode) {
        switch (mode) {
            case 0x00: return 0; // 关闭
            case 0x01: return 1; // 通透
            case 0x02: return 2; // 普通
            case 0x03: return 3; // 深度
            case 0x10: return 4; // 实验
            case 0x11: return 5; // 抗风噪
            default: return -1;
        }
    }

    // ==========================================
    // ============ 功能指令区 ===================
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
    private void setAncOff() {
        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x00, 0x00});
        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
    }
    private void setAncTransparency() {
        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x01, 0x00});
        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
    }
    private void setAncNormal() {
        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x02, 0x00});
        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
    }
    private void setAncDeep() {
        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x03, 0x00});
        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
    }
    private void setAncExperimental() {
        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x10, 0x00});
        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
    }
    private void setAncWind() {
        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x11, 0x00});
        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
    }

    // --- EQ 控制 ---
    private void setEqBlue() {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x07, 0x02, 0x00});
    }
    private void setEqBalanced() {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x07, 0x02, 0x01});
    }
    private void setEqBass() {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x07, 0x02, 0x02});
    }
    private void setEqPure() {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x07, 0x02, 0x03});
    }
    private void setEqGame() {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x07, 0x02, 0x04});
    }

    // --- 高级功能 ---
    private void setGameModeOn() {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x08, 0x02, 0x01});
    }
    private void setGameModeOff() {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x08, 0x02, 0x00});
    }
    private void setLowLatencyOn() {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x06, 0x02, 0x01});
    }
    private void setLowLatencyOff() {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x06, 0x02, 0x00});
    }
    private void setDualConnOn() {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x05, 0x02, 0x01});
    }
    private void setDualConnOff() {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x05, 0x02, 0x00});
    }
    private void setCodecLHDC() {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x04, 0x02, 0x01});
    }
    private void setCodecAAC() {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x04, 0x02, 0x00});
    }
    private void setAntiWindOn() {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, (byte)0xE1, 0x02, 0x01});
    }
    private void setAntiWindOff() {
        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, (byte)0xE1, 0x02, 0x00});
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothController != null) {
            bluetoothController.close();
        }
    }
}