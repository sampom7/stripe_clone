package com.stripeclone.payment;

/**
 * Whether confirming an intent also captures it.
 *
 * <p>Automatic is the common case: confirm and the money moves. Manual holds the funds and
 * waits for an explicit capture, which is what you want when you can't ship for a few days
 * and don't want to charge before you do.
 */
public enum CaptureMethod {

    AUTOMATIC("automatic"),
    MANUAL("manual");

    private final String wireValue;

    CaptureMethod(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static CaptureMethod fromWire(String value) {
        if (value == null) {
            return AUTOMATIC;
        }
        for (CaptureMethod method : values()) {
            if (method.wireValue.equals(value)) {
                return method;
            }
        }
        throw new IllegalArgumentException("Unknown capture method: " + value);
    }
}
