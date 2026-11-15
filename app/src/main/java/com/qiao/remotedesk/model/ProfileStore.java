package com.qiao.remotedesk.model;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.text.SimpleDateFormat;

/**
 * 连接配置与界面状态的持久化，全部落在 filesDir 下。
 * 写采用「临时文件 + 原子 rename」，读损坏时备份坏文件并返回空列表。
 */
public class ProfileStore {

    private static final String NL = new String(new char[] { 10 });

    private final Context ctx;

    public ProfileStore(Context ctx) {
        this.ctx = ctx != null ? ctx.getApplicationContext() : null;
    }

    /** 配置文件目录（filesDir）。 */
    public File configDir() {
        return ctx == null ? new File(".") : ctx.getFilesDir();
    }

    /** 读 connections.json；损坏则备份成 .corrupt-时间戳 并返回空列表。 */
    public List<ConnectionProfile> load() {
        File file = new File(configDir(), "connections.json");
        if (!file.exists()) {
            return new ArrayList<ConnectionProfile>();
        }
        try {
            String json = readText(file);
            JSONObject doc = new JSONObject(json);
            JSONArray arr = doc.optJSONArray("profiles");
            List<ConnectionProfile> list = new ArrayList<ConnectionProfile>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    ConnectionProfile p = ConnectionProfile.fromJson(item);
                    p.afterLoad();
                    list.add(p);
                }
            }
            return list;
        } catch (Throwable t) {
            backupCorrupt(file);
            return new ArrayList<ConnectionProfile>();
        }
    }

    /** 顶层对象格式为 {"profiles":[...]}；临时文件 + 原子 rename。 */
    public void save(List<ConnectionProfile> profiles) {
        try {
            JSONObject doc = new JSONObject();
            JSONArray arr = new JSONArray();
            if (profiles != null) {
                for (ConnectionProfile p : profiles) {
                    if (p != null) {
                        arr.put(p.toJson());
                    }
                }
            }
            doc.put("profiles", arr);
            atomicWrite(new File(configDir(), "connections.json"), doc.toString());
        } catch (Throwable t) {
            // 写失败不影响主流程
        }
    }

    /** 读 ui.json；任何异常都降级为默认 UiState。 */
    public UiState loadUiState() {
        try {
            File file = new File(configDir(), "ui.json");
            if (!file.exists()) {
                return new UiState();
            }
            return UiState.fromJson(new JSONObject(readText(file)));
        } catch (Throwable t) {
            return new UiState();
        }
    }

    /** 写 ui.json（独立 JSON，不加外层包装）。 */
    public void saveUiState(UiState s) {
        try {
            UiState state = s != null ? s : new UiState();
            atomicWrite(new File(configDir(), "ui.json"), state.toJson().toString());
        } catch (Throwable t) {
            // 记不住状态不算故障
        }
    }

    /** 追加一行到 logs/yyyy-MM-dd.log。 */
    public void appendLog(String line) {
        try {
            File logs = new File(configDir(), "logs");
            if (!logs.exists()) {
                logs.mkdirs();
            }
            String name = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()) + ".log";
            File file = new File(logs, name);
            FileOutputStream out = new FileOutputStream(file, true);
            try {
                out.write((String.valueOf(line) + NL).getBytes("UTF-8"));
                out.flush();
            } finally {
                closeQuietly(out);
            }
        } catch (Throwable t) {
            // 日志写失败不影响主流程
        }
    }

    private void backupCorrupt(File file) {
        try {
            if (!file.exists()) {
                return;
            }
            String stamp = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(new Date());
            File backup = new File(file.getPath() + ".corrupt-" + stamp);
            file.renameTo(backup);
        } catch (Throwable t) {
            // 备份失败就放弃
        }
    }

    private void atomicWrite(File dst, String text) throws java.io.IOException {
        File dir = dst.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        File tmp = new File(dst.getPath() + ".tmp");
        writeText(tmp, text);
        if (!tmp.renameTo(dst)) {
            // 个别文件系统 rename 不能覆盖已存在文件，退化为直接写
            writeText(dst, text);
            tmp.delete();
        }
    }

    private static String readText(File f) throws java.io.IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            closeQuietly(in);
        }
    }

    private static void writeText(File f, String text) throws java.io.IOException {
        FileOutputStream out = new FileOutputStream(f, false);
        try {
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
