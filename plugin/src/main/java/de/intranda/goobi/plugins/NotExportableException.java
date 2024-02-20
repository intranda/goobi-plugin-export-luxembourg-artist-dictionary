package de.intranda.goobi.plugins;

public class NotExportableException extends Exception {

    private static final long serialVersionUID = -6968469538222042107L;

    public NotExportableException() {
        super();
    }

    public NotExportableException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    public NotExportableException(String message, Throwable cause) {
        super(message, cause);
    }

    public NotExportableException(String message) {
        super(message);
    }

    public NotExportableException(Throwable cause) {
        super(cause);
    }

}
