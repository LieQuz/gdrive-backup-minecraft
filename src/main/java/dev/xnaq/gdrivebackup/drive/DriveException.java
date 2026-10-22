package dev.xnaq.gdrivebackup.drive;

public class DriveException extends RuntimeException {
    public DriveException(String message) {
        super(message);
    }

    public DriveException(String message, Throwable cause) {
        super(message, cause);
    }
}
