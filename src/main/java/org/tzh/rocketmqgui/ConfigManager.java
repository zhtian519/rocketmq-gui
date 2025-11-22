package org.tzh.rocketmqgui;

import java.io.*;
import java.util.*;

public class ConfigManager {
    private static final String CONFIG_FILE = System.getProperty("user.home") + File.separator + ".rocketmq_fx_config";
    private Properties props = new Properties();

    public ConfigManager() {
        load();
    }

    private void load() {
        try (InputStream in = new FileInputStream(CONFIG_FILE)) {
            props.load(in);
        } catch (IOException ignored) {
            // 文件不存在忽略
        }
    }

    public void saveHistory(String addr) {
        String history = props.getProperty("history", "");
        Set<String> set = new LinkedHashSet<>(Arrays.asList(history.split(",")));
        set.remove("");
        set.add(addr);
        props.setProperty("history", String.join(",", set));
        
        try (OutputStream out = new FileOutputStream(CONFIG_FILE)) {
            props.store(out, "RocketMQ FX Config");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public List<String> getHistory() {
        String history = props.getProperty("history", "");
        if (history.isEmpty()) return new ArrayList<>();
        List<String> list = new ArrayList<>(Arrays.asList(history.split(",")));
        Collections.reverse(list); // 最近的排前面
        return list;
    }
}