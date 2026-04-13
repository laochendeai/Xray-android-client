package cc.hifly.xrayandroid.runtime;

import android.content.Context;
import android.content.SharedPreferences;

public final class RuntimePreferences {
    private static final String PREFS = "xray_runtime_prefs";
    private static final String KEY_SELECTED_NODE_ID = "selected_node_id";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_RUNNING_NODE_ID = "running_node_id";
    private static final String KEY_RUNNING_NODE_NAME = "running_node_name";
    private static final String KEY_STATUS_TITLE = "status_title";
    private static final String KEY_STATUS_DETAIL = "status_detail";

    private RuntimePreferences() {
    }

    public static Snapshot load(Context context) {
        SharedPreferences prefs = prefs(context);
        Snapshot snapshot = new Snapshot();
        snapshot.selectedNodeId = prefs.getString(KEY_SELECTED_NODE_ID, "");
        snapshot.running = prefs.getBoolean(KEY_RUNNING, false);
        snapshot.runningNodeId = prefs.getString(KEY_RUNNING_NODE_ID, "");
        snapshot.runningNodeName = prefs.getString(KEY_RUNNING_NODE_NAME, "");
        snapshot.statusTitle = prefs.getString(KEY_STATUS_TITLE, "");
        snapshot.statusDetail = prefs.getString(KEY_STATUS_DETAIL, "");
        return snapshot;
    }

    public static void saveSelectedNodeId(Context context, String nodeId) {
        prefs(context).edit().putString(KEY_SELECTED_NODE_ID, value(nodeId)).apply();
    }

    public static void markStarting(Context context, String nodeId, String nodeName, String title, String detail) {
        prefs(context).edit()
                .putBoolean(KEY_RUNNING, false)
                .putString(KEY_RUNNING_NODE_ID, value(nodeId))
                .putString(KEY_RUNNING_NODE_NAME, value(nodeName))
                .putString(KEY_STATUS_TITLE, value(title))
                .putString(KEY_STATUS_DETAIL, value(detail))
                .apply();
    }

    public static void markRunning(Context context, String nodeId, String nodeName, String title, String detail) {
        prefs(context).edit()
                .putBoolean(KEY_RUNNING, true)
                .putString(KEY_RUNNING_NODE_ID, value(nodeId))
                .putString(KEY_RUNNING_NODE_NAME, value(nodeName))
                .putString(KEY_STATUS_TITLE, value(title))
                .putString(KEY_STATUS_DETAIL, value(detail))
                .apply();
    }

    public static void markStopped(Context context, String title, String detail) {
        prefs(context).edit()
                .putBoolean(KEY_RUNNING, false)
                .putString(KEY_RUNNING_NODE_ID, "")
                .putString(KEY_RUNNING_NODE_NAME, "")
                .putString(KEY_STATUS_TITLE, value(title))
                .putString(KEY_STATUS_DETAIL, value(detail))
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }

    public static final class Snapshot {
        public String selectedNodeId;
        public boolean running;
        public String runningNodeId;
        public String runningNodeName;
        public String statusTitle;
        public String statusDetail;
    }
}
