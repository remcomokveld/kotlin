// Fixpoint generic in Java: Enum<T extends Enum<T>>
fun test(a : java.lang.annotation.RetentionPolicy) {

}

fun test() {
  java.util.Collections.<!NEW_INFERENCE_NO_INFORMATION_FOR_PARAMETER!>emptyList<!>()
  val a : Collection<String>? = java.util.Collections.emptyList()
}

fun test(a : java.lang.Comparable<Int>) {

}

fun test(a : java.util.ArrayList<Int>) {

}

fun test(a : java.lang.Class<Int>) {

}
