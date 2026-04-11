package cc.hifly.xrayandroid.model;

public enum SubscriptionSourceType {
    URL("url"),
    MANUAL("manual"),
    FILE("file");

    private final String value;

    SubscriptionSourceType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
