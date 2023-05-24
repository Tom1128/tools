package com.ckj.inter;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class SSHConsole {
    private static volatile boolean cancelFlag = false;

    public static void main(String[] args) throws Exception {
        //在这里输入主机ip、用户名、密码
        String hostname = "";
        String username = "root";
        String password = "";

        ExecutorService executor = null;
                Session session = null;
        ChannelShell channelShell = null;
        try {
            JSch jsch = new JSch();

            // 创建 SSH 会话
            session = jsch.getSession(username, hostname, 22);
            session.setPassword(password);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setTimeout(60000); // 设置超时时间为60秒

            // 连接 SSH 会话
            session.connect();

            // 创建 Shell 通道
            channelShell = (ChannelShell) session.openChannel("shell");
            channelShell.setInputStream(System.in);
            channelShell.setOutputStream(System.out);

            // 连接 Shell 通道
            channelShell.connect();

            // 创建线程池和任务队列
             executor = Executors.newScheduledThreadPool(2);
            BlockingQueue<String> commandQueue = new LinkedBlockingQueue<>(1);


            // 创建命令执行任务
            Session finalSession = session;
            Callable<String> commandTask = () -> {
                String command = commandQueue.take(); // 从任务队列获取命令
                return executeCommand(finalSession, command);
            };

            // 提交命令执行任务并获取 Future 对象
            AtomicReference<Future<String>> commandFuture = new AtomicReference<>();
            Scanner scanner = new Scanner(System.in);


            // 监听用户输入的命令
            Session finalSession1 = session;
            ExecutorService finalExecutor = executor;
            executor.submit(() -> {
                while (true) {

                    try {
                        String command = scanner.nextLine();

                        if (command.equalsIgnoreCase("exit")) {
                            break;
                        }

                        if (command.equals("")) {
                            continue;
                        }


                        if (command.startsWith("cd ")) {
                            changeDirectory(finalSession1, command.substring(3)); // 记录当前目录
                            continue;
                        }
                        commandQueue.clear();
                        commandQueue.offer(command); // 将命令添加到任务队列

                        if (commandFuture.get() != null) {
                            try {
                                String output = commandFuture.get().get(); // 获取上一个命令执行结果
                                System.out.println(output);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        if (!finalExecutor.isShutdown()) {
                            commandFuture.set(finalExecutor.submit(commandTask)); // 提交新的命令执行任务
                        } else {
                            //TODO
                        }
//                        commandFuture.set(executor.submit(commandTask)); // 提交新的命令执行任务
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });


        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // 等待用户输入线程和命令执行线程完成
            executor.shutdown();
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
            // 关闭 Shell 通道和 SSH 会话

            channelShell.disconnect();
            session.disconnect();
        }
    }

    private static String executeCommand(Session session, String command) {
        StringBuilder output = new StringBuilder();

        try {
            ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
            channelExec.setCommand(command);
            channelExec.setInputStream(null);
            channelExec.setErrStream(System.err);

            InputStream inputStream = channelExec.getInputStream();
            channelExec.connect();

            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                String line = new String(buffer, 0, bytesRead);
                if (!line.startsWith(command)) {  // 排除命令本身的回显
                    output.append(line);
                }
            }
            channelExec.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return output.toString();
    }

    private static String changeDirectory(Session session, String directory) {
        String newDirectory = null;

        try {
            if (!session.isConnected()) {
                return "session end!!!!";
            }
            ChannelShell channelShell = (ChannelShell) session.openChannel("shell");
            channelShell.connect();

            OutputStream outputStream = channelShell.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(channelShell.getInputStream()));

            // 发送cd命令
            String command;
            if (directory.startsWith("/")) {
                // 绝对路径，直接使用输入的目录
                command = "cd " + directory + "\n";
            } else {
                // 相对路径，拼接当前目录和输入的目录
                command = "cd $(pwd)/" + directory + "\n";
            }
            outputStream.write(command.getBytes());
            outputStream.flush();

            // 读取命令输出，直到提示符出现
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.endsWith("$ ") || line.endsWith("# ")) { // 根据实际的提示符进行匹配
                    newDirectory = line.substring(0, line.length() - 2); // 去除提示符
                    break;
                }
            }

            channelShell.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return newDirectory;
    }

}


