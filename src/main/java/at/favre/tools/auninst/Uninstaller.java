package at.favre.tools.auninst;

import at.favre.tools.auninst.parser.AdbDevice;
import at.favre.tools.auninst.parser.AdbDevicesParser;
import at.favre.tools.auninst.parser.InstalledPackagesParser;
import at.favre.tools.auninst.parser.PackageMatcher;
import at.favre.tools.auninst.ui.Arg;
import at.favre.tools.auninst.ui.CLIParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class Uninstaller {

    public static void main(String[] args) {
        Arg arguments = CLIParser.parse(args);

        if (arguments != null) {
            execute(arguments);
        }
    }

    private static void execute(Arg arguments) {
        List<CmdUtil.Result> executedCommands = new ArrayList<>();

        try {
            AdbLocationFinder.LocationResult adbLocation = new AdbLocationFinder().find(arguments.adbPath);

            executedCommands.add(runAdbCommand(new String[]{"start-server"}, adbLocation));

            CmdUtil.Result devicesCmdResult = runAdbCommand(new String[]{"devices", "-l"}, adbLocation);
            executedCommands.add(devicesCmdResult);
            List<AdbDevice> devices = new AdbDevicesParser().parse(devicesCmdResult.out);

            if (!devices.isEmpty()) {
                checkSpecificDevice(devices, arguments);

                String statusLog = "Found " + devices.size() + " device(s). Use filter '" + arguments.filterString + "'.";

                if (arguments.keepData) {
                    statusLog += " Keep data/caches.";
                }

                if (adbLocation.location == AdbLocationFinder.Location.WIN_DEFAULT) {
                    statusLog += " Adb not found in PATH, use default location: " + adbLocation.args[2] + ".";
                }

                statusLog += "\n";

                logLoud(statusLog);
            }

            int deviceCount = 0;
            int successUninstallCount = 0;
            int failureUninstallCount = 0;
            for (AdbDevice device : devices) {
                if (arguments.device == null || arguments.device.equals(device.serial)) {
                    CmdUtil.Result packagesCmdResult = runAdbCommand(new String[]{"-s", device.serial, "shell", "pm list packages -f"}, adbLocation);
                    executedCommands.add(packagesCmdResult);

                    String modelName = "Device";
                    if (device.model != null) {
                        modelName = device.model;
                    }

                    String deviceLog = modelName + " [" + device.serial + "]";

                    if (device.status != AdbDevice.Status.OK) {
                        deviceLog += ": " + device.status;
                    }

                    if (arguments.skipEmulators && device.isEmulator) {
                        deviceLog += " (skip)";
                    }

                    log(deviceLog, arguments);
                    if (device.status == AdbDevice.Status.OK && (!arguments.skipEmulators || !device.isEmulator)) {
                        deviceCount++;

                        List<String> allPackages = new InstalledPackagesParser().parse(packagesCmdResult.out);
                        Set<String> filteredPackages = new PackageMatcher(allPackages).findMatches(
                                PackageMatcher.parseFiltersArg(arguments.filterString));

                        for (String filteredPackage : filteredPackages) {
                            String uninstallStatus = "\t";
                            if (!arguments.dryRun) {
                                CmdUtil.Result uninstallCmdResult = runAdbCommand(createUninstallCmd(device,filteredPackage,arguments), adbLocation);
                                executedCommands.add(uninstallCmdResult);

                                uninstallStatus += filteredPackage + "\t" + (uninstallCmdResult.out != null ? uninstallCmdResult.out.trim() : "");
                                if (InstalledPackagesParser.wasSuccessfulUninstalled(uninstallCmdResult.out)) {
                                    successUninstallCount++;
                                } else {
                                    failureUninstallCount++;
                                }
                            } else {
                                uninstallStatus += filteredPackage + "\t [Dryrun]";
                            }
                            log(uninstallStatus, arguments);
                        }

                        if (filteredPackages.isEmpty()) {
                            log("\t No apps found for given filter", arguments);
                        }
                    }
                    log("", arguments);
                }
            }


            if (deviceCount == 0) {
                logLoud("No ready devices found.");
                if(hasUnauthorizedDevices(devices)) {
                    logLoud("Check if you authorized your computer on your Android device. See http://stackoverflow.com/questions/23081263");
                }
            } else {
                logLoud(generateReport(deviceCount, successUninstallCount, failureUninstallCount));
            }

            if(arguments.debug) {
                logLoud(getCommandHistory(executedCommands));
            }
        } catch (Exception e) {
            logErr(e.getMessage());

            if(arguments.debug) {
                logErr(getCommandHistory(executedCommands));
            } else {
                logErr("Run with '-debug' parameter to get additional information.");
            }
        }
    }

    private static String[] createUninstallCmd(AdbDevice device, String filteredPackage, Arg arguments) {
        String[] basicCmd = new String[]{"-s", device.serial, "uninstall"};

        if(arguments.keepData) {
            basicCmd = CmdUtil.concat(basicCmd,new String[] {"-k"});
        }

        return CmdUtil.concat(basicCmd,new String[] {filteredPackage});
    }

    private static boolean hasUnauthorizedDevices(List<AdbDevice> devices) {
        for (AdbDevice device : devices) {
            if(device.status == AdbDevice.Status.UNAUTHORIZED) {
                return true;
            }
        }
        return false;
    }

    private static String getCommandHistory(List<CmdUtil.Result> executedCommands) {
        StringBuilder sb = new StringBuilder("\nCmd history for debugging purpose:\n-----------------------\n");
        for (CmdUtil.Result executedCommand : executedCommands) {
            sb.append(executedCommand.toString());
        }
        return sb.toString();
    }


    private static String generateReport(int deviceCount, int successUninstallCount, int failureUninstallCount) {
        String report = String.format(Locale.US, "%d apps were uninstalled on %d device(s).", successUninstallCount, deviceCount);
        if (failureUninstallCount > 0) {
            report += String.format(Locale.US, "%d apps could not be uninstalled due to errors.", failureUninstallCount);
        }
        return report;
    }

    private static void checkSpecificDevice(List<AdbDevice> devices, Arg arguments) {
        if (arguments.device != null) {
            boolean found = false;
            for (AdbDevice device : devices) {
                if (device.serial.equals(arguments.device) && device.status == AdbDevice.Status.OK) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalArgumentException("There is no ready device attached with id '" + arguments.device + "'. Found devices: " + devices);
            }
        }
    }

    private static void logErr(String msg) {
        System.err.println(msg);
    }

    private static void logLoud(String msg) {
        System.out.println(msg);
    }

    private static void log(String msg, Arg arg) {
        if (!arg.quiet) {
            System.out.println(msg);
        }
    }

    private static CmdUtil.Result runAdbCommand(String[] adbArgs, AdbLocationFinder.LocationResult locationResult) {
        return CmdUtil.runCmd(CmdUtil.concat(locationResult.args, adbArgs));
    }
}
