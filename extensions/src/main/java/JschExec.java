import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;

public class JschExec {
    public static void main(String[] args) throws JSchException, IOException {
        var input = new String[]{"Hi"};

        var session = new JSch().getSession("desktop-mvt20j7\\josef", "192.168.56.7");
        session.setPassword("josefina4891");
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect();

        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        try {
            channel.setCommand("$input | ForEach-Object { Write-Output \"You entered: $_\" }");

            final InputStream in = channel.getInputStream();
            final InputStream errStream = channel.getErrStream();
            final OutputStream out = channel.getOutputStream();
            PrintWriter writer = new PrintWriter(out, true);
            channel.connect();

            int printed = 0;
            while (true) {

                String response = null;
                String errorMessage = null;

                while (in.available() > 0) {
                    byte[] tmp = new byte[1024];
                    int i = in.read(tmp, 0, 1024);
                    if (i < 0) break;
                    response = new String(tmp, 0, i, Charset.defaultCharset());
                }

                while (errStream.available() > 0) {
                    byte[] tmp = new byte[1024];
                    int i = errStream.read(tmp, 0, 1024);
                    if (i < 0) break;
                    errorMessage = new String(tmp, Charset.defaultCharset());
                }

                var exitStatus = channel.getExitStatus();
                if (exitStatus != -1) {
                    System.out.println("exit-status: " + exitStatus);
                }
                if (response != null) {
                    System.out.println("stdout: " + response);
                }
                if (errorMessage != null) {
                    System.out.println("stderr: " + errorMessage);
                }

                if (channel.isClosed()) {
                    if (channel.getExitStatus() == 0) {
                        System.out.println("command ended successfully");
                        return;
                    } else {
                        throw new RuntimeException("command ended in exit-status:" + channel.getExitStatus() +
                                " with error message: " + errorMessage);
                    }

                } else if (printed < input.length) {
                    writer.println(input[printed++]);
                }

                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            channel.disconnect();
        }
    }
}