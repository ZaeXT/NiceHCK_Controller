package com.zaext.nicehckcontroller;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;

import com.elvishew.xlog.XLog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
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
    private View chipEqVocal;
    private View chipEqFine;
    private MaterialButton codecSBCButton;
    private Button btnRefreshStatus;

    private final ArrayList<BluetoothDevice> pairedDevicesList = new ArrayList<>();
    private final ArrayList<String> pairedDeviceNames = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        final View statusBarSpacer = findViewById(R.id.status_bar_spacer);
        ViewCompat.setOnApplyWindowInsetsListener(statusBarSpacer, (v, windowInsets) -> {
            Insets statusBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars());
            v.getLayoutParams().height = statusBarsInsets.top;
            v.requestLayout();
            return windowInsets;
        });

        chipEqFine = findViewById(R.id.chip_eq_fine);
        chipEqVocal = findViewById(R.id.chip_eq_vocal);

        // 初始化 UI 控件
        spinnerDevices = findViewById(R.id.spinner_devices);
        textStatus = findViewById(R.id.text_status);
        textBattery = findViewById(R.id.text_battery);
        btnRefreshStatus = findViewById(R.id.btn_refresh_status);
        codecSBCButton = findViewById(R.id.btn_codec_sbc);

        findViewById(R.id.btn_export_log).setOnClickListener(v -> exportLog());

        // 初始化蓝牙控制器单例
        bluetoothController = BluetoothController.getInstance(this);

        // 设置状态监听器
        setupStateListener();

        // 设置刷新按钮点击事件
        btnRefreshStatus.setOnClickListener(v -> refreshDeviceStatus());

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "该设备不支持蓝牙", Toast.LENGTH_SHORT).show();
            XLog.w("Bluetooth not supported");
            finish();
            return;
        }

        checkAndRequestPermissions();

        // 自动连接设备
        new Thread(() -> {
            boolean isConnected = bluetoothController.connectDefaultDevice();
            runOnUiThread(() -> {
                if (isConnected) {
                    updateConnectionStatus(true);
                    refreshDeviceStatus();
                    XLog.d("Auto-connected to default device");
                }
            });
        }).start();

        bluetoothController.setFirmwareListener((mainVersion, subVersion) -> {
            XLog.i("Connected device firmware version: " + mainVersion + "." + subVersion);
            runOnUiThread(() -> {
                if (subVersion >= 8) {
                    chipEqFine.setVisibility(View.VISIBLE);
                    chipEqVocal.setVisibility(View.VISIBLE);
                    codecSBCButton.setVisibility(MaterialButton.VISIBLE);
                    XLog.i("Enabled extra features for firmware version >= 8");
                } else {
                    chipEqFine.setVisibility(View.GONE);
                    chipEqVocal.setVisibility(View.GONE);
                }
            });
        });

        initSwitches();

        XLog.d("MainActivity created");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bluetoothController != null && bluetoothController.isConnected()) {
            XLog.d("Refreshing device status on resume");
            bluetoothController.queryFirmwareVersion();
            new android.os.Handler().postDelayed(() -> {
                bluetoothController.queryBattery();
                bluetoothController.queryAncMode();
            }, 200);
            refreshDeviceStatus();
        }
    }

    private void exportLog() {
        try {
            File logDir = new File(getExternalFilesDir(null), "logs");
            File[] files = logDir.listFiles();
            if (files == null || files.length == 0) {
                Toast.makeText(this, "没有日志文件可导出", Toast.LENGTH_SHORT).show();
                return;
            }
            File latestLog = files[0];
            for (File file : files) {
                if (file.lastModified() > latestLog.lastModified()) {
                    latestLog = file;
                }
            }

            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", latestLog);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            XLog.i("Exporting log file: " + latestLog.getAbsolutePath());
            startActivity(Intent.createChooser(shareIntent, "分享日志文件"));
        } catch (Exception e) {
            XLog.e("Failed to export log file", e);
            Toast.makeText(this, "导出日志文件失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    private void updateConnectionStatus(boolean connected) {
        if (textStatus != null) {
            if (connected) {
                textStatus.setText("状态：✅ 已连接");
                textStatus.setTextColor(getResources().getColor(R.color.status_connected));
                XLog.i("Device connected");
            } else {
                textStatus.setText("状态：❌ 连接失败");
                textStatus.setTextColor(getResources().getColor(R.color.status_disconnected));
                XLog.w("Device connection failed");
            }
        }
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
                XLog.w("Bluetooth permissions denied");
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

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, pairedDeviceNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevices.setAdapter(adapter);
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

    private void initSwitches() {
        MaterialSwitch switchGameMode = findViewById(R.id.switch_game_mode);
        MaterialSwitch switchLowLatency = findViewById(R.id.switch_low_latency);
        MaterialSwitch switchDualConn = findViewById(R.id.switch_dual_conn);
        MaterialSwitch switchWindSuppression = findViewById(R.id.switch_wind_suppression);

        switchGameMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                bluetoothController.setFeatureMode(NiceHckProtocol.Feature.GAME_MODE, isChecked);
                bluetoothController.queryGameMode();
            }
        });

        switchLowLatency.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                bluetoothController.setFeatureMode(NiceHckProtocol.Feature.LOW_LATENCY, isChecked);
                bluetoothController.queryLowLatencyMode();
            }
        });

        switchDualConn.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                bluetoothController.setFeatureMode(NiceHckProtocol.Feature.DUAL_CONN, isChecked);
                bluetoothController.queryDualConnMode();
            }
        });
        switchWindSuppression.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                bluetoothController.setFeatureMode(NiceHckProtocol.Feature.WIND_SUPPRESSION, isChecked);
                bluetoothController.queryWindSuppressionMode();
            }
        });
    }

    private void setupStateListener() {
        bluetoothController.setStateListener(new BluetoothController.StateListener() {
            @Override
            public void onBatteryChanged(int left, int right, int caseLevel) {
                runOnUiThread(() -> updateBatteryUI(left, right, caseLevel));
            }

            @Override
            public void onAncModeChanged(NiceHckProtocol.AncMode mode) {
                runOnUiThread(() -> updateAncModeUI(mode));
            }

            @Override
            public void onEqModeChanged(NiceHckProtocol.EqMode mode) {
                runOnUiThread(() -> updateEqModeUI(mode));
            }

            @Override
            public void onGameModeChanged(boolean enabled) {
                runOnUiThread(() -> updateGameModeUI(enabled));
            }

            @Override
            public void onLowLatencyChanged(boolean enabled) {
                runOnUiThread(() -> updateLowLatencyUI(enabled));
            }

            @Override
            public void onDualConnChanged(boolean enabled) {
                runOnUiThread(() -> updateDualConnUI(enabled));
            }

            @Override
            public void onWindSuppressionChanged(boolean enabled) {
                runOnUiThread(() -> updateWindSuppressionUI(enabled));
            }
        });
    }

    private void refreshDeviceStatus() {
        if (!bluetoothController.isConnected()) {
            Toast.makeText(this, "设备未连接", Toast.LENGTH_SHORT).show();
            XLog.i("Cannot refresh status: device not connected");
            return;
        }

        bluetoothController.queryBattery();
        new android.os.Handler().postDelayed(() -> {
            bluetoothController.queryAncMode();
        }, 100);
        new android.os.Handler().postDelayed(() -> {
            bluetoothController.queryEqMode();
        }, 200);
        new android.os.Handler().postDelayed(() -> {
            bluetoothController.queryGameMode();
        }, 300);
        new android.os.Handler().postDelayed(() -> {
            bluetoothController.queryLowLatencyMode();
        }, 400);
        new android.os.Handler().postDelayed(() -> {
            bluetoothController.queryDualConnMode();
        }, 500);
        new android.os.Handler().postDelayed(() -> {
            bluetoothController.queryWindSuppressionMode();
        }, 600);
    }

    private void updateBatteryUI(int left, int right, int caseLevel) {
        if (textBattery != null) {
            if (left >= 0 && right >= 0) {
                textBattery.setText("🔋 电量: 左 " + left + "% | 右 " + right + "% | 盒 " + (caseLevel >= 0 ? caseLevel + "%（上次）" : "未知"));
            } else {
                textBattery.setText("🔋 电量: 未知");
            }
        }
    }

    private void updateAncModeUI(NiceHckProtocol.AncMode mode) {
        com.google.android.material.chip.ChipGroup chipGroup = findViewById(R.id.chip_group_anc);

        int chipId = -1;
        switch (mode) {
            case OFF: chipId = R.id.chip_anc_off; break;
            case TRANSPARENT: chipId = R.id.chip_anc_trans; break;
            case NORMAL: chipId = R.id.chip_anc_normal; break;
            case DEEP: chipId = R.id.chip_anc_deep; break;
            case EXPERIMENT: chipId = R.id.chip_anc_exp; break;
            case WIND_SUPPRESSION: chipId = R.id.chip_anc_wind; break;
        }
        if (chipId != -1) {
            chipGroup.check(chipId);
        }
    }

    private void updateEqModeUI(NiceHckProtocol.EqMode mode) {
        com.google.android.material.chip.ChipGroup chipGroup = findViewById(R.id.chip_group_eq);

        int chipId = -1;
        switch (mode) {
            case BLUE: chipId = R.id.chip_eq_blue; break;
            case BALANCED: chipId = R.id.chip_eq_balanced; break;
            case BASS: chipId = R.id.chip_eq_bass; break;
            case PURE: chipId = R.id.chip_eq_pure; break;
            case GAME: chipId = R.id.chip_eq_game; break;
            case FINE: chipId = R.id.chip_eq_fine; break;
            case VOCAL: chipId = R.id.chip_eq_vocal; break;
        }
        if (chipId != -1) {
            chipGroup.check(chipId);
        }
    }

    private void updateGameModeUI(boolean enabled) {
        MaterialSwitch switchGameMode = findViewById(R.id.switch_game_mode);
        switchGameMode.setChecked(enabled);
    }

    private void updateLowLatencyUI(boolean enabled) {
        MaterialSwitch switchLowLatency = findViewById(R.id.switch_low_latency);
        switchLowLatency.setChecked(enabled);
    }

    private void updateDualConnUI(boolean enabled) {
        MaterialSwitch switchDualConn = findViewById(R.id.switch_dual_conn);
        switchDualConn.setChecked(enabled);
    }

    private void updateWindSuppressionUI(boolean enabled) {
        MaterialSwitch switchWindSuppression = findViewById(R.id.switch_wind_suppression);
        switchWindSuppression.setChecked(enabled);
    }


    // ==========================================
    // ============ 功能指令区 ===================
    // ==========================================

    private void sendCommand(byte[] packet) {
        if (!bluetoothController.isConnected()) {
            com.google.android.material.snackbar.Snackbar.make(findViewById(android.R.id.content),
                    "耳机未连接", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
            XLog.w("Cannot send command: device not connected");
            return;
        }
        XLog.d("Sent command: " + bytesToHex(packet));
        bluetoothController.sendRaw(packet);
        Toast.makeText(this, "指令已发送", Toast.LENGTH_SHORT).show();
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    // --- 降噪控制 ---
    public void setAncOff(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x00, 0x00});
//        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
        bluetoothController.setAncMode(NiceHckProtocol.AncMode.OFF);
    }
    public void setAncTransparency(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x01, 0x00});
//        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
        bluetoothController.setAncMode(NiceHckProtocol.AncMode.TRANSPARENT);
    }
    public void setAncNormal(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x02, 0x00});
//        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
        bluetoothController.setAncMode(NiceHckProtocol.AncMode.NORMAL);
    }
    public void setAncDeep(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x03, 0x00});
//        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
        bluetoothController.setAncMode(NiceHckProtocol.AncMode.DEEP);
    }
    public void setAncExperimental(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x10, 0x00});
//        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
        bluetoothController.setAncMode(NiceHckProtocol.AncMode.EXPERIMENT);
    }
    public void setAncWind(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x11, 0x00});
//        sendCommand(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
        bluetoothController.setAncMode(NiceHckProtocol.AncMode.WIND_SUPPRESSION);
    }

    // --- EQ 控制 ---
    public void setEqBlue(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x07, 0x02, 0x00});
        bluetoothController.setEqMode(NiceHckProtocol.EqMode.BLUE);
    }
    public void setEqBalanced(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x07, 0x02, 0x01});
        bluetoothController.setEqMode(NiceHckProtocol.EqMode.BALANCED);
    }
    public void setEqBass(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x07, 0x02, 0x02});
        bluetoothController.setEqMode(NiceHckProtocol.EqMode.BASS);
    }
    public void setEqPure(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x07, 0x02, 0x03});
        bluetoothController.setEqMode(NiceHckProtocol.EqMode.PURE);
    }
    public void setEqGame(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x07, 0x02, 0x04});
        bluetoothController.setEqMode(NiceHckProtocol.EqMode.GAME);
    }
    public void setEqFine(View v) {
        bluetoothController.setEqMode(NiceHckProtocol.EqMode.FINE);
    }
    public void setEqVocal(View v) {
        bluetoothController.setEqMode(NiceHckProtocol.EqMode.VOCAL);
    }

    // --- 高级功能 ---
    public void setGameModeOn(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x08, 0x02, 0x01});
        bluetoothController.setFeatureMode(NiceHckProtocol.Feature.GAME_MODE, true);
    }
    public void setGameModeOff(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x08, 0x02, 0x00});
        bluetoothController.setFeatureMode(NiceHckProtocol.Feature.GAME_MODE, false);
    }
    public void setLowLatencyOn(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x06, 0x02, 0x01});
        bluetoothController.setFeatureMode(NiceHckProtocol.Feature.LOW_LATENCY, true);
    }
    public void setLowLatencyOff(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x06, 0x02, 0x00});
        bluetoothController.setFeatureMode(NiceHckProtocol.Feature.LOW_LATENCY, false);

    }
    public void setDualConnOn(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x05, 0x02, 0x01});
        bluetoothController.setFeatureMode(NiceHckProtocol.Feature.DUAL_CONN, true);
    }
    public void setDualConnOff(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x05, 0x02, 0x00});
        bluetoothController.setFeatureMode(NiceHckProtocol.Feature.DUAL_CONN, false);
    }
    public void setCodecLHDC(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x04, 0x02, 0x01});
        if (bluetoothController.getFirmwareSubVersion() >= 8) {
            bluetoothController.setCodec(NiceHckProtocol.Codec.LHDC);
        } else {
            bluetoothController.setFeatureMode(NiceHckProtocol.Feature.CODEC_LHDC, true);
        }
    }
    public void setCodecAAC(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x04, 0x02, 0x00});
        if (bluetoothController.getFirmwareSubVersion() >= 8) {
            bluetoothController.setCodec(NiceHckProtocol.Codec.AAC);
        } else {
            bluetoothController.setFeatureMode(NiceHckProtocol.Feature.CODEC_LHDC, false);
        }
    }
    public void setCodecSBC(View v) {
        bluetoothController.setCodec(NiceHckProtocol.Codec.SBC);
    }
    public void setAntiWindOn(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, (byte)0xE1, 0x02, 0x01});
        bluetoothController.setFeatureMode(NiceHckProtocol.Feature.WIND_SUPPRESSION, true);
    }
    public void setAntiWindOff(View v) {
//        sendCommand(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, (byte)0xE1, 0x02, 0x00});
        bluetoothController.setFeatureMode(NiceHckProtocol.Feature.WIND_SUPPRESSION, false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothController != null) {
            bluetoothController.close();
        }
    }
}