package ddf.support.command.system;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;

import ddf.support.command.system.api.SystemMonitor;

@Command(scope = "system", name = "wait-for-ready", description = "Waits for the system to be in a ready state for operations.")
@Service
public class BootReadyCommand implements Action {

  @Reference
  SystemMonitor systemMonitor;

  @Override
  public Object execute() throws Exception {
    systemMonitor.waitForBundles();
    return null;
  }
}
