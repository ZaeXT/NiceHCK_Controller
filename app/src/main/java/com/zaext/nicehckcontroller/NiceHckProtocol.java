package com.zaext.nicehckcontroller;

public class NiceHckProtocol {
    public enum AncMode {
        OFF(0x00, "关闭"),
        TRANSPARENT(0x01, "通透"),
        NORMAL(0x02, "普通降噪"),
        DEEP(0x03, "深度降噪"),
        EXPERIMENT(0x10,"试验性降噪"),
        WIND_SUPPRESSION(0x11,"风噪抑制");

        public final int value;
        public final String label;

        AncMode(int value, String label) {
            this.value = value;
            this.label = label;
        }

        public static AncMode fromValue(int value) {
            for (AncMode mode : values()) {
                if (mode.value == value) {
                    return mode;
                }
            }
            return OFF; // 或抛出异常
        }
    }
    public enum EqMode {
        BLUE(0x00, "悔恨之泪"),
        BALANCED(0x01, "均衡中正"),
        BASS(0x02, "欧美澎湃"),
        PURE(0x03, "真律还原"),
        GAME(0x04, "游戏优化"),
        FINE(0x05, "细腻佳音"),
        VOCAL(0x06, "温婉人声");

        public final int value;
        public final String label;
        EqMode(int value, String label) { this.value = value; this.label = label; }
        public static EqMode fromValue(int value) {
            for (EqMode mode : values()) {
                if (mode.value == value) {
                    return mode;
                }
            }
            return BALANCED; // 或抛出异常
        }
    }

    public enum Codec {
        AAC(0x00),
        LHDC(0x01),
        SBC(0x02);
        public final int value;
        Codec(int value) { this.value = value; }
    }

    public enum Feature {
        GAME_MODE(Op.GAME_MODE_SET),
        LOW_LATENCY(Op.LOW_LATENCY_SET),
        DUAL_CONN(Op.DUAL_CONN_SET),
        IN_EAR_DETECT(Op.IN_EAR_SET),
        CODEC_LHDC(0x04), // For Firmware lower than 408
        WIND_SUPPRESSION(Op.WIND_SUPPRESSION_SET);

        public final int opCode;
        Feature(int opCode) { this.opCode = opCode; }
    }

    public static class Op {
        public static final int VERSION = 0x0003;      // 固件版本
        public static final int BATTERY = 0x0005;      // 电量
        public static final int ANC_SET = 0x0201;      // 设置降噪 (抓包显示小端序为 01 02)
        public static final int ANC_QUERY = 0x0101;    // 查询降噪
        public static final int EQ_SET = 0x0207;       // 设置EQ
        public static final int EQ_QUERY = 0x0107;     // 查询EQ
        public static final int GAME_MODE_SET = 0x0208;    // 游戏模式
        public static final int GAME_MODE_QUERY = 0x0108;  // 查询游戏模式
        public static final int LOW_LATENCY_SET = 0x0206;  // 低延迟
        public static final int LOW_LATENCY_QUERY = 0x0106; // 查询低延迟q
        public static final int DUAL_CONN_SET = 0x0205;    // 双连接
        public static final int DUAL_CONN_QUERY = 0x0105;  // 查询双连接
        public static final int IN_EAR_SET = 0x0209;       // 入耳检测
        public static final int IN_EAR_QUERY = 0x0109;     // 查询入耳检测
        public static final int CODEC = 0x0204;        // 编码切换
        public static final int WIND_SUPPRESSION_SET = 0x02E1;    // 抗风噪开关
        public static final int WIND_SUPPRESSION_QUERY = 0x01E1;  // 查询抗风噪
        public static final int FULL_STATE = 0x0103;   // 全量状态查询
    }
}
