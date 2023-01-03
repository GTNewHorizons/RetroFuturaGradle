package test

@AttachValues(Array("hello", "HELLO", "world", "WORLD"))
class TestScala {
  val hello = "HELLO";
  // currently unsupported, doesn't seem that useful
  val WORLD = "world";
}
