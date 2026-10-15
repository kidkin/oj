package top.hcode.hoj.edu;

public class EduException extends RuntimeException {
    private final int status;
    public EduException(int status, String message) { super(message); this.status = status; }
    public int getStatus() { return status; }
}
