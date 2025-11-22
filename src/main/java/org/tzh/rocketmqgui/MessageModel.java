package org.tzh.rocketmqgui;

import javafx.beans.property.SimpleStringProperty;

public class MessageModel {
    private final SimpleStringProperty msgId;
    private final SimpleStringProperty topic;
    private final SimpleStringProperty tag;
    private final SimpleStringProperty time;
    private final SimpleStringProperty body;

    public MessageModel(String msgId, String topic, String tag, String time, String body) {
        this.msgId = new SimpleStringProperty(msgId);
        this.topic = new SimpleStringProperty(topic);
        this.tag = new SimpleStringProperty(tag);
        this.time = new SimpleStringProperty(time);
        this.body = new SimpleStringProperty(body);
    }

    public String getMsgId() {
        return msgId.get();
    }

    public String getTopic() {
        return topic.get();
    }

    public String getTag() {
        return tag.get();
    }

    public String getTime() {
        return time.get();
    }

    public String getBody() {
        return body.get();
    }
}