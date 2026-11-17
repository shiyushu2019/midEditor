package com.example;

/**
 * 示例入口类，用来验证 Maven Wrapper 工具链是否可用。
 */
public final class App {

    private App() {
    }

    public static int sum(int a, int b) {
        return a + b;
    }

    public static void main(String[] args) {
        System.out.printf("Hello, Java %s! 1 + 2 = %d%n",
                System.getProperty("java.version"), sum(1, 2));
    }
}
