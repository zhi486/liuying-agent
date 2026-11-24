package com.healthyagent.running.agent.cloud;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/** 仅在合成测试目录持锁，供父进程验证跨进程互斥与异常退出释放。 */
public final class SummaryLockProcess {
    public static void main(String[] args) throws Exception {
        try (FileChannel channel = FileChannel.open(Paths.get(args[0]),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            System.out.println("LOCKED");
            System.out.flush();
            System.in.read();
        }
    }
}
