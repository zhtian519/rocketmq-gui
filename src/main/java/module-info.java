module org.tzh.rocketmqgui {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.apache.rocketmq.client;
    requires org.apache.rocketmq.tools;
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;
    requires org.slf4j;
    requires java.logging;

    exports org.tzh.rocketmqgui;
    opens org.tzh.rocketmqgui to javafx.fxml;
}
