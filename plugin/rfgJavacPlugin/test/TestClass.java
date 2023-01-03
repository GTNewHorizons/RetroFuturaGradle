package test;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@interface AttachValues
{
    String[] value();
}

@AttachValues({"hello", "HELLO", "world", "WORLD"})
public class TestClass {
    public final String field = "HELLO";
    public final String WORLD = "world";
}
