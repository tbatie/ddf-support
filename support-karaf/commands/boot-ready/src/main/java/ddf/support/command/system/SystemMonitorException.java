package ddf.support.command.system;

public class SystemMonitorException extends Exception {

  public SystemMonitorException() {
    super();
  }

  public SystemMonitorException(String message) {
    super(message);
  }

  public SystemMonitorException(String message, Throwable throwable) {
    super(message, throwable);
  }


  public SystemMonitorException(Throwable throwable) {
    super(throwable);
  }
}
