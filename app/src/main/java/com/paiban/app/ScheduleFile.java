package com.paiban.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;

/** 按文件魔数自动识别 xlsx/xls 并解析 */
public class ScheduleFile {

    public static ScheduleModel.ParseResult parse(InputStream in) throws Exception {
        PushbackInputStream pin = new PushbackInputStream(in, 16);
        byte[] head = new byte[8];
        int n = pin.read(head);
        if (n > 0) pin.unread(head, 0, n);

        if (n >= 4) {
            boolean zip = (head[0] == 0x50 && head[1] == 0x4B);            // PK..
            boolean ole = (head[0] == (byte) 0xD0 && head[1] == (byte) 0xCF
                    && head[2] == 0x11 && head[3] == (byte) 0xE0);          // D0CF11E0
            if (ole) return XlsParser.parse(pin);
            if (zip) return XlsxParser.parse(pin);
        }
        // 兜底:尝试按 xlsx 解析
        try {
            return XlsxParser.parse(pin);
        } catch (Exception e) {
            throw new IOException("无法识别的表格文件格式(仅支持 .xls / .xlsx)");
        }
    }
}
