package org.tzh.rocketmqgui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.admin.ConsumeStats;
import org.apache.rocketmq.common.admin.TopicStatsTable;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.protocol.body.Connection;
import org.apache.rocketmq.common.protocol.body.ConsumerConnection;
import org.apache.rocketmq.common.protocol.body.SubscriptionGroupWrapper;
import org.apache.rocketmq.common.subscription.SubscriptionGroupConfig;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RocketMQFXApp extends Application {

    private RocketMQManager mqManager;
    private ConfigManager configManager = new ConfigManager();
    private TextArea logArea;
    private ComboBox<String> nameSrvCombo;
    private Button connectBtn;
    private Button disconnectBtn;
    private ListView<String> topicListView;
    private ListView<String> groupListView;
    // TableViews
    private TableView<MessageModel> producerTable;
    private TableView<MessageModel> consumerTable;
    // Global Data
    private final Set<String> allTopics = new HashSet<>();
    private final ObservableList<String> globalTopicData = FXCollections.observableArrayList();
    // [新增] 生产者和消费者的下拉框组件 (提升为成员变量，方便赋值)
    private ComboBox<String> producerTopicCombo;
    private ComboBox<String> consumerTopicCombo;
    // Charts
    private XYChart.Series<String, Number> topicOffsetSeries;
    private Button startMonitorBtn;
    private ScheduledExecutorService monitorService;

    public static void main(String[] args) {
        launch(args);
    }

    private void enableSearch(ComboBox<String> comboBox) {
        comboBox.setEditable(true);

        // 创建一个过滤列表，包裹全局数据
        FilteredList<String> filteredItems = new FilteredList<>(globalTopicData, p -> true);
        comboBox.setItems(filteredItems);

        // 监听输入框的文本变化
        comboBox.getEditor().textProperty().addListener((obs, oldVal, newVal) -> {
            final String selected = comboBox.getSelectionModel().getSelectedItem();

            // 如果当前的文本就是选中的项，则不进行过滤（避免误触）
            if (selected != null && selected.equals(newVal)) {
                return;
            }

            // 运行在 UI 线程
            Platform.runLater(() -> {
                // 设置过滤规则：不区分大小写包含
                filteredItems.setPredicate(item -> {
                    if (newVal == null || newVal.isEmpty()) return true;
                    return item.toLowerCase().contains(newVal.toLowerCase());
                });

                // 过滤后如果列表不为空且下拉框未显示，则展开
                if (!filteredItems.isEmpty() && !comboBox.isShowing()) {
                    comboBox.show();
                }
            });
        });
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("RocketMQ Admin Pro (All-in-One)");

        // Top Bar
        HBox topBox = createTopBar();

        // Main Tabs
        TabPane tabPane = new TabPane();
        tabPane.getTabs().addAll(
                createDashboardTab(),  // Feature: Dashboard
                createTopicTab(),
                createGroupTab(),      // Feature: Reset Offset & DLQ
                createProducerTab(),
                createConsumerTab(),   // Feature: SQL Filter
                createMessageQueryTab()
        );

        // Bottom Log
        logArea = new TextArea();
        logArea.setPrefHeight(80);
        logArea.setEditable(false);

        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        root.getChildren().addAll(topBox, tabPane, new Label("System Log:"), logArea);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        Scene scene = new Scene(root, 1100, 800);
        primaryStage.setScene(scene);
        primaryStage.show();

        setConnectedState(false);
    }

    // --- Top Bar & Connection ---
    private HBox createTopBar() {
        HBox topBox = new HBox(10);
        topBox.setPadding(new Insets(5));
        nameSrvCombo = new ComboBox<>();
        nameSrvCombo.setEditable(true);
        nameSrvCombo.getItems().addAll(configManager.getHistory());
        if (!nameSrvCombo.getItems().isEmpty()) nameSrvCombo.getSelectionModel().select(0);
        nameSrvCombo.setPrefWidth(300);

        connectBtn = new Button("Connect");
        connectBtn.setOnAction(e -> connect());
        disconnectBtn = new Button("Disconnect");
        disconnectBtn.setOnAction(e -> disconnect());
        topBox.getChildren().addAll(new Label("NameServer:"), nameSrvCombo, connectBtn, disconnectBtn);
        return topBox;
    }

    private void connect() {
        String addr = nameSrvCombo.getEditor().getText();
        configManager.saveHistory(addr);
        new Thread(() -> {
            try {
                stopMonitorService();
                if (mqManager != null) mqManager.shutdown();
                mqManager = new RocketMQManager(addr);
                log("Connected to " + addr);
                refreshTopics();
                startMonitor(); // Start Dashboard Chart
                setConnectedState(true);
            } catch (Exception e) {
                logError("Connection Error", e);
            }
        }).start();
    }

    private void disconnect() {
        new Thread(() -> {
            try {
                stopMonitorService();
                if (mqManager != null) {
                    mqManager.disconnect();
                    mqManager = null;
                }
                log("Disconnected from NameServer");
                setConnectedState(false);
            } catch (Exception e) {
                logError("Disconnect Error", e);
            }
        }).start();
    }

    // --- Tab 1: Dashboard (Monitor) ---
    private Tab createDashboardTab() {
        Tab tab = new Tab("Dashboard");
        tab.setClosable(false);

        VBox content = new VBox(10);
        content.setPadding(new Insets(20));

        // 1. 图表设置
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("Time");
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Max Offset");

        LineChart<String, Number> lineChart = new LineChart<>(xAxis, yAxis);
        lineChart.setTitle("Topic Volume Monitor");
        lineChart.setAnimated(false); // 关闭动画以提高实时性能

        topicOffsetSeries = new XYChart.Series<>();
        topicOffsetSeries.setName("No Topic Selected");
        lineChart.getData().add(topicOffsetSeries);

        // 2. 控制栏
        HBox controls = new HBox(10);

        // [修正] 使用 ComboBox 并启用搜索与全局数据绑定
        ComboBox<String> topicSelector = new ComboBox<>();
        topicSelector.setPromptText("Select or Search Topic");
        topicSelector.setPrefWidth(300);

        // 关键调用：绑定全局数据源并启用搜索
        enableSearch(topicSelector);

        startMonitorBtn = new Button("Start Monitoring");
        startMonitorBtn.setOnAction(e -> {
            // 获取用户选择或输入的 Topic
            String t = topicSelector.getEditor().getText();
            if (t == null || t.isEmpty()) {
                t = topicSelector.getValue();
            }

            if (t != null && !t.isEmpty()) {
                // 更新图表标题
                topicOffsetSeries.setName(t + " Total Offset");
                // 清空旧数据
                topicOffsetSeries.getData().clear();
                // [重要] 启动定时任务 (确保您有 startMonitor 方法)
                startMonitor(t);
            }
        });

        controls.getChildren().addAll(new Label("Monitor Topic:"), topicSelector, startMonitorBtn);
        content.getChildren().addAll(controls, lineChart);
        tab.setContent(content);
        return tab;
    }

    private void startMonitor() {
        stopMonitorService();
        monitorService = Executors.newSingleThreadScheduledExecutor();
        monitorService.scheduleAtFixedRate(() -> {
            if (mqManager == null) return;
            String topicName = topicOffsetSeries.getName().split(" ")[0]; // Hacky way to get topic
            if (topicName.equals("Select")) return;

            try {
                TopicStatsTable stats = mqManager.getTopicStats(topicName);
                long totalOffset = stats.getOffsetTable().values().stream()
                        .mapToLong(topicOffset -> topicOffset.getMaxOffset()).sum();

                String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
                Platform.runLater(() -> {
                    if (topicOffsetSeries.getData().size() > 20) topicOffsetSeries.getData().remove(0);
                    topicOffsetSeries.getData().add(new XYChart.Data<>(time, totalOffset));
                });
            } catch (Exception e) {
                // ignore errors during monitor
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

    // [修正] 接收 Topic 参数，不再从标题解析
    private void startMonitor(String topic) {
        // 1. 如果之前有监控任务在运行，先停止它
        stopMonitorService();

        // 2. 创建新的调度线程池
        monitorService = Executors.newSingleThreadScheduledExecutor();

        // 3. 启动定时任务 (每 3 秒执行一次)
        monitorService.scheduleAtFixedRate(() -> {
            if (mqManager == null) return;

            try {
                // 直接使用传入的 topic 参数查询状态
                TopicStatsTable stats = mqManager.getTopicStats(topic);

                // 计算总 Offset (所有队列的最大 Offset 之和)
                long totalOffset = stats.getOffsetTable().values().stream()
                        .mapToLong(topicOffset -> topicOffset.getMaxOffset()).sum();

                String time = new SimpleDateFormat("HH:mm:ss").format(new Date());

                // 更新 UI (必须在 JavaFX 线程)
                Platform.runLater(() -> {
                    // 保持图表只显示最近 20 个点，防止无限增长
                    if (topicOffsetSeries.getData().size() > 20) {
                        topicOffsetSeries.getData().remove(0);
                    }
                    topicOffsetSeries.getData().add(new XYChart.Data<>(time, totalOffset));
                });
            } catch (Exception e) {
                // 忽略网络波动导致的单词查询失败，打印日志可选
                // e.printStackTrace();
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

    // --- Tab 3: Consumer Group (Reset Offset & DLQ) ---
    private Tab createGroupTab() {
        Tab tab = new Tab("Groups");
        tab.setClosable(false);

        SplitPane split = new SplitPane();

        // Left: List
        VBox left = new VBox(10);
        left.setPadding(new Insets(10));
        ListView<String> groupList = new ListView<>();
        Button refreshBtn = new Button("Refresh");
        refreshBtn.setOnAction(e -> loadGroups(groupList));
        left.getChildren().addAll(refreshBtn, groupList);
        groupListView = groupList;

        // Right: Actions
        VBox right = new VBox(10);
        right.setPadding(new Insets(10));
        TextArea infoArea = new TextArea();
        infoArea.setEditable(false);
        infoArea.setPrefHeight(200);

        // Action Buttons
        HBox actions = new HBox(10);
        Button checkBtn = new Button("Check Status");
        Button resetBtn = new Button("⚠️ Reset Offset");
        Button dlqBtn = new Button("💀 Check DLQ");

        actions.getChildren().addAll(checkBtn, resetBtn, dlqBtn);

        right.getChildren().addAll(new Label("Group Details:"), infoArea, new Label("Operations:"), actions);
        split.getItems().addAll(left, right);
        split.setDividerPositions(0.3);

        // Event Handlers
        checkBtn.setOnAction(e -> {
            String g = groupList.getSelectionModel().getSelectedItem();
            if (g == null) return;
            checkGroupStatus(g, infoArea);
        });

        resetBtn.setOnAction(e -> {
            String g = groupList.getSelectionModel().getSelectedItem();
            if (g == null) {
                log("Select a group first");
                return;
            }
            showResetOffsetDialog(g);
        });

        dlqBtn.setOnAction(e -> {
            String g = groupList.getSelectionModel().getSelectedItem();
            if (g == null) return;
            checkDLQ(g);
        });

        tab.setContent(split);
        return tab;
    }

    private void showResetOffsetDialog(String group) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Reset Offset: " + group);
        dialog.setHeaderText("Select Topic and Time to rewind consumption.");

        ButtonType okBtn = new ButtonType("Reset", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        ComboBox<String> topicCombo = new ComboBox<>();
        topicCombo.getItems().addAll(allTopics);
        DatePicker datePicker = new DatePicker(LocalDate.now());
        TextField timeField = new TextField("00:00:00");

        grid.add(new Label("Topic:"), 0, 0);
        grid.add(topicCombo, 1, 0);
        grid.add(new Label("Date:"), 0, 1);
        grid.add(datePicker, 1, 1);
        grid.add(new Label("Time:"), 0, 2);
        grid.add(timeField, 1, 2);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == okBtn) {
                try {
                    String topic = topicCombo.getValue();
                    LocalDate date = datePicker.getValue();
                    String timeStr = timeField.getText();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date d = sdf.parse(date.toString() + " " + timeStr);

                    mqManager.resetOffset(topic, group, d.getTime());
                    log("Offset reset successfully for " + topic);
                } catch (Exception ex) {
                    logError("Reset Failed", ex);
                }
            }
            return null;
        });
        dialog.showAndWait();
    }

    private void checkDLQ(String group) {
        String dlqTopic = mqManager.getDLQTopic(group);
        new Thread(() -> {
            try {
                TopicStatsTable stats = mqManager.getTopicStats(dlqTopic);
                long total = stats.getOffsetTable().values().stream().mapToLong(o -> o.getMaxOffset()).sum();
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("DLQ Status");
                    alert.setHeaderText("DLQ Topic: " + dlqTopic);
                    alert.setContentText("Total Messages in DLQ: " + total +
                            "\n\nTo re-consume, go to Consumer Tab and subscribe to " + dlqTopic);
                    alert.showAndWait();
                });
            } catch (Exception e) {
                logError("DLQ Check Failed (Maybe no DLQ exists)", e);
            }
        }).start();
    }


    // --- Helper Methods ---
    private Tab createConsumerTab() {
        Tab tab = new Tab("Consumer");
        tab.setClosable(false);
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        HBox controls = new HBox(10);

        // 定义输入控件
        TextField groupField = new TextField("FX_VIEW_GROUP");
        consumerTopicCombo = new ComboBox<>(); // 确保在类成员变量里定义了它
        consumerTopicCombo.setPromptText("Select or Search Topic");
        consumerTopicCombo.setPrefWidth(200);
        enableSearch(consumerTopicCombo); // 启用搜索

        ComboBox<String> filterType = new ComboBox<>();
        filterType.getItems().addAll("TAG", "SQL92");
        filterType.setValue("TAG");
        filterType.setPrefWidth(80);

        TextField subField = new TextField("*");
        subField.setPromptText("Expr");
        HBox.setHgrow(subField, Priority.ALWAYS);

        // [修改] 定义切换按钮
        Button actionBtn = new Button("Start");
        actionBtn.setPrefWidth(80);
        Button clearBtn = new Button("Clear");

        controls.getChildren().addAll(
                new Label("G:"), groupField,
                new Label("T:"), consumerTopicCombo,
                filterType, subField, actionBtn, clearBtn
        );

        consumerTable = createMessageTable();
        addContextMenu(consumerTable); // 确保右键菜单已添加

        // [核心修改] 按钮点击逻辑：Start / Stop 切换
        actionBtn.setOnAction(e -> {
            // 1. 如果当前是 "Start"，执行启动逻辑
            if (actionBtn.getText().equals("Start")) {
                String g = groupField.getText();
                String t = consumerTopicCombo.getEditor().getText();
                if (t == null || t.isEmpty()) t = consumerTopicCombo.getValue();
                String sub = subField.getText();
                boolean isSql = "SQL92".equals(filterType.getValue());

                if (t == null || t.isEmpty()) {
                    log("Please select a topic first!");
                    return;
                }

                String finalT = t;

                // 锁定 UI，防止运行时修改参数
                setInputsDisable(true, groupField, consumerTopicCombo, filterType, subField);

                new Thread(() -> {
                    try {
                        mqManager.startConsumer(g, finalT, sub, isSql, msg -> {
                            Platform.runLater(() -> {
                                String offsetMsgId;
                                try {
                                    // 利用 StoreHost (Broker地址) 和 CommitLogOffset (物理偏移量) 计算 ID
                                    offsetMsgId = MessageDecoder.createMessageId(msg.getStoreHost(), msg.getCommitLogOffset());
                                } catch (Exception e2) {
                                    // 万一计算失败（极少见），降级使用默认 ID
                                    offsetMsgId = msg.getMsgId();
                                }
                                consumerTable.getItems().add(0, new MessageModel(
                                        offsetMsgId, msg.getTopic(), msg.getTags(),
                                        new SimpleDateFormat("HH:mm:ss").format(new Date()),
                                        new String(msg.getBody())
                                ));
                            });
                        });

                        Platform.runLater(() -> {
                            log("Consumer Started: " + finalT);
                            actionBtn.setText("Stop");
                            // 变成红色，提示正在运行
                            actionBtn.setStyle("-fx-background-color: #ff6666; -fx-text-fill: white;");
                        });
                    } catch (Exception ex) {
                        logError("Start Consumer Failed", ex);
                        // 失败需恢复 UI
                        Platform.runLater(() -> setInputsDisable(false, groupField, consumerTopicCombo, filterType, subField));
                    }
                }).start();

            } else {
                // 2. 如果当前是 "Stop"，执行停止逻辑
                new Thread(() -> {
                    try {
                        mqManager.stopConsumer();
                        Platform.runLater(() -> {
                            log("Consumer Stopped.");
                            actionBtn.setText("Start");
                            actionBtn.setStyle(""); // 恢复默认样式
                            // 解锁 UI
                            setInputsDisable(false, groupField, consumerTopicCombo, filterType, subField);
                        });
                    } catch (Exception ex) {
                        logError("Stop Consumer Failed", ex);
                    }
                }).start();
            }
        });

        clearBtn.setOnAction(e -> consumerTable.getItems().clear());

        content.getChildren().addAll(controls, consumerTable);
        tab.setContent(content);
        return tab;
    }

    // [新增辅助方法] 批量禁用/启用控件
    private void setInputsDisable(boolean disable, Control... controls) {
        for (Control c : controls) {
            c.setDisable(disable);
        }
    }

    private void checkGroupStatus(String group, TextArea area) {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                ConsumerConnection conn = mqManager.getConsumerConnection(group);
                sb.append("Online Clients: ").append(conn.getConnectionSet().size()).append("\n");
                for (Connection c : conn.getConnectionSet()) sb.append(" - ").append(c.getClientAddr()).append("\n");

                ConsumeStats stats = mqManager.getConsumeStats(group);
                sb.append("\nTotal Lag: ").append(stats.computeTotalDiff()).append("\n");
            } catch (Exception e) {
                sb.append("Error: ").append(e.getMessage());
            }
            Platform.runLater(() -> area.setText(sb.toString()));
        }).start();
    }

    private void loadGroups(ListView<String> list) {
        new Thread(() -> {
            try {
                SubscriptionGroupWrapper wrapper = mqManager.getAllSubscriptionGroups();
                Platform.runLater(() -> {
                    list.getItems().clear();
                    for (SubscriptionGroupConfig c : wrapper.getSubscriptionGroupTable().values())
                        list.getItems().add(c.getGroupName());
                });
            } catch (Exception e) {
                logError("Fetch Groups Failed", e);
            }
        }).start();
    }

    private void refreshTopics() {
        if (mqManager == null) return;
        new Thread(() -> {
            try {
                // 获取最新的 Topic 集合
                Set<String> topics = mqManager.getTopicList();

                // [修改] 更新全局 ObservableList
                Platform.runLater(() -> {
                    // 先清空再添加，触发监听事件
                    globalTopicData.setAll(topics);

                    // 同时更新 Topic 管理页面的列表 (如果有的话)
                    if (topicListView != null) {
                        topicListView.getItems().setAll(topics);
                    }

                    log("Topics refreshed: " + topics.size());
                });
            } catch (Exception e) {
                logError("Fetch Topics Error", e);
            }
        }).start();
    }

    // ... (Other Tabs like Producer/Topic/Query are similar to previous version, kept brief for space)

    private TableView<MessageModel> createMessageTable() {
        TableView<MessageModel> table = new TableView<>();
        TableColumn<MessageModel, String> id = new TableColumn<>("Msg ID");
        id.setCellValueFactory(new PropertyValueFactory<>("msgId"));
        TableColumn<MessageModel, String> tag = new TableColumn<>("Tag");
        tag.setCellValueFactory(new PropertyValueFactory<>("tag"));
        TableColumn<MessageModel, String> time = new TableColumn<>("Time");
        time.setCellValueFactory(new PropertyValueFactory<>("time"));
        TableColumn<MessageModel, String> body = new TableColumn<>("Body");
        body.setCellValueFactory(new PropertyValueFactory<>("body"));

        id.setPrefWidth(150);
        body.setPrefWidth(400);
        table.getColumns().addAll(id, tag, time, body);
        return table;
    }

    private Tab createTopicTab() {
        Tab tab = new Tab("Topic");
        tab.setClosable(false);
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        HBox tools = new HBox(10);
        TextField newTopicField = new TextField();
        newTopicField.setPromptText("New Topic Name");
        Button createBtn = new Button("Create");
        Button refreshBtn = new Button("Refresh");
        tools.getChildren().addAll(newTopicField, createBtn, refreshBtn);

        topicListView = new ListView<>();

        createBtn.setOnAction(e -> {
            String t = newTopicField.getText();
            if (t.isEmpty()) return;
            new Thread(() -> {
                try {
                    mqManager.createTopic(t);
                    log("Created: " + t);
                    refreshTopics();
                } catch (Exception ex) {
                    logError("Create Fail", ex);
                }
            }).start();
        });
        refreshBtn.setOnAction(e -> refreshTopics());

        content.getChildren().addAll(tools, topicListView);
        tab.setContent(content);
        return tab;
    }

    private Tab createProducerTab() {
        Tab tab = new Tab("Producer");
        tab.setClosable(false);
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        HBox inputRow = new HBox(10);

        // [修改] 使用 ComboBox 替代 TextField
        producerTopicCombo = new ComboBox<>();
        producerTopicCombo.setPromptText("Select or Search Topic");
        producerTopicCombo.setPrefWidth(250);
        enableSearch(producerTopicCombo); // 启用搜索功能

        TextField tagField = new TextField("*");
        tagField.setPrefWidth(80);
        TextField bodyField = new TextField("Hello RocketMQ");
        Button sendBtn = new Button("Send");

        inputRow.getChildren().addAll(
                new Label("Topic:"), producerTopicCombo, // 使用 combo
                new Label("Tag:"), tagField,
                new Label("Body:"), bodyField,
                sendBtn
        );
        HBox.setHgrow(bodyField, Priority.ALWAYS);

        producerTable = createMessageTable(); // 假设你保留了之前的表格创建逻辑
        producerTable.setPlaceholder(new Label("No messages sent yet"));
        addContextMenu(producerTable); // <--- 加上这句
        sendBtn.setOnAction(e -> {
            if (mqManager == null) return;

            // [修改] 获取值的逻辑变了
            String t = producerTopicCombo.getEditor().getText(); // 获取输入框文字
            // 如果用户是选择的，也可以用 getValue()，但 getText() 最稳妥
            if (t == null || t.isEmpty()) {
                t = producerTopicCombo.getValue();
            }

            String tag = tagField.getText();
            String body = bodyField.getText();

            // 这里的 finalT 是为了传入 lambda
            String finalT = t;
            new Thread(() -> {
                try {
                    SendResult result = mqManager.sendMessage(finalT, tag, body);
                    Platform.runLater(() -> {
                        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
                        String queryableId = result.getOffsetMsgId();
                        if (queryableId == null || queryableId.isEmpty()) {
                            queryableId = result.getMsgId();
                        }
                        producerTable.getItems().add(0, new MessageModel(
                                queryableId, finalT, tag, sdf.format(new Date()), body
                        ));
                        log("Sent: " + result.getSendStatus());
                    });
                } catch (Exception ex) {
                    logError("Send Failed", ex);
                }
            }).start();
        });

        content.getChildren().addAll(inputRow, producerTable);
        tab.setContent(content);
        return tab;
    }

    private Tab createMessageQueryTab() {
        Tab tab = new Tab("Msg Query");
        tab.setClosable(false);
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        HBox searchBox = new HBox(10);
        TextField msgIdField = new TextField();
        msgIdField.setPromptText("Enter Message ID");
        msgIdField.setPrefWidth(300);
        Button searchBtn = new Button("Query Details");
        searchBox.getChildren().addAll(new Label("Msg ID:"), msgIdField, searchBtn);

        TextArea detailArea = new TextArea();
        detailArea.setEditable(false);
        detailArea.setFont(javafx.scene.text.Font.font("Monospaced", 12));
        VBox.setVgrow(detailArea, Priority.ALWAYS);

        searchBtn.setOnAction(e -> {
            String id = msgIdField.getText().trim();
            if (id.isEmpty() || mqManager == null) return;
            detailArea.setText("Searching...");
            new Thread(() -> {
                try {
                    MessageExt msg = mqManager.viewMessage(id);
                    StringBuilder sb = new StringBuilder();
                    sb.append("MsgId: ").append(msg.getMsgId()).append("\n");
                    sb.append("Topic: ").append(msg.getTopic()).append("\n");
                    sb.append("Tags:  ").append(msg.getTags()).append("\n");
                    sb.append("Keys:  ").append(msg.getKeys()).append("\n");
                    sb.append("Broker: ").append(msg.getStoreHost()).append("\n");
                    sb.append("QueueId: ").append(msg.getQueueId()).append("\n");
                    sb.append("Offset:  ").append(msg.getQueueOffset()).append("\n");
                    sb.append("BornTime: ").append(new Date(msg.getBornTimestamp())).append("\n");
                    sb.append("StoreTime: ").append(new Date(msg.getStoreTimestamp())).append("\n");
                    sb.append("--------------------------------------------------\n");
                    sb.append("Body:\n").append(new String(msg.getBody()));

                    Platform.runLater(() -> detailArea.setText(sb.toString()));
                } catch (Exception ex) {
                    Platform.runLater(() -> detailArea.setText("Not Found or Error: " + ex.getMessage()));
                }
            }).start();
        });

        content.getChildren().addAll(searchBox, detailArea);
        tab.setContent(content);
        return tab;
    }

    private void log(String m) {
        Platform.runLater(() -> logArea.appendText(m + "\n"));
    }

    private void logError(String m, Exception e) {
        Platform.runLater(() -> logArea.appendText("ERR: " + m + " - " + e.getMessage() + "\n"));
        e.printStackTrace();
    }

    private void stopMonitorService() {
        if (monitorService != null && !monitorService.isShutdown()) {
            monitorService.shutdownNow();
        }
        monitorService = null;
    }

    private void setConnectedState(boolean connected) {
        Platform.runLater(() -> {
            if (connectBtn != null) connectBtn.setDisable(connected);
            if (disconnectBtn != null) disconnectBtn.setDisable(!connected);
            if (startMonitorBtn != null) startMonitorBtn.setDisable(!connected);
            if (topicListView != null) topicListView.setDisable(!connected);
            if (groupListView != null) groupListView.setDisable(!connected);
            if (producerTopicCombo != null) producerTopicCombo.setDisable(!connected);
            if (consumerTopicCombo != null) consumerTopicCombo.setDisable(!connected);
        });
    }

    @Override
    public void stop() {
        stopMonitorService();
        if (mqManager != null) {
            try {
                mqManager.disconnect();
            } catch (Exception e) {
                mqManager.shutdown();
            }
        }
    }

    // [新增] 通用右键菜单方法
    private void addContextMenu(TableView<MessageModel> table) {
        ContextMenu menu = new ContextMenu();

        // 1. 复制 Msg ID
        MenuItem copyId = new MenuItem("Copy Msg ID");
        copyId.setOnAction(e -> {
            MessageModel item = table.getSelectionModel().getSelectedItem();
            if (item != null) copyToClipboard(item.getMsgId());
        });

        // 2. 复制 Body (文本)
        MenuItem copyBody = new MenuItem("Copy Body");
        copyBody.setOnAction(e -> {
            MessageModel item = table.getSelectionModel().getSelectedItem();
            if (item != null) copyToClipboard(item.getBody());
        });

        // 3. 复制全部详情 (方便调试)
        MenuItem copyAll = new MenuItem("Copy Row Details");
        copyAll.setOnAction(e -> {
            MessageModel item = table.getSelectionModel().getSelectedItem();
            if (item != null) {
                String content = String.format("ID: %s\nTag: %s\nTime: %s\nBody: %s",
                        item.getMsgId(), item.getTag(), item.getTime(), item.getBody());
                copyToClipboard(content);
            }
        });

        menu.getItems().addAll(copyId, copyBody, new SeparatorMenuItem(), copyAll);
        table.setContextMenu(menu);
    }

    // [辅助] 写入剪贴板
    private void copyToClipboard(String content) {
        if (content == null) return;
        ClipboardContent clipboardContent = new ClipboardContent();
        clipboardContent.putString(content);
        Clipboard.getSystemClipboard().setContent(clipboardContent);
    }


}