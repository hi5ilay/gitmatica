package me.zly2006.lvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

final class IntegrationTestSupport
{
    private IntegrationTestSupport()
    {
    }

    static void run(String name, ThrowingRunnable test) throws Exception
    {
        try
        {
            test.run();
            System.out.println("[PASS] " + name);
        }
        catch (Throwable throwable)
        {
            System.err.println("[FAIL] " + name);
            throwable.printStackTrace(System.err);

            if (throwable instanceof Exception exception)
            {
                throw exception;
            }

            throw new RuntimeException(throwable);
        }
    }

    static void assertFileContains(Path file, String expected) throws Exception
    {
        assertTrue(Files.exists(file), "expected file to exist: " + file);
        String content = Files.readString(file);
        assertTrue(content.contains(expected), "expected " + file + " to contain: " + expected);
    }

    static void assertEquals(Object expected, Object actual, String message)
    {
        if (!Objects.equals(expected, actual))
        {
            throw new AssertionError(message + " expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void assertNotNull(Object actual, String message)
    {
        if (actual == null)
        {
            throw new AssertionError(message);
        }
    }

    static void assertTrue(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    interface ThrowingRunnable
    {
        void run() throws Exception;
    }
}
