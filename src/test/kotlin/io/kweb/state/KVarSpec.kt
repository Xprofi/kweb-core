package io.kweb.state

import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec

object KVarSpec : StringSpec({
    "a KVar with value `dog`" {
        data class Foo(val bar : String)
        val f = Foo("dog")
        val kvf = KVar(f)
        "should have field with value `dog`" {
            kvf.value.bar shouldBe "dog"
        }

        val kvfp = kvf.property(Foo::bar)

        kvfp.value = "cat"

        "should have modified the underlying KVar" {
            kvf.value.bar shouldBe "cat"
        }
    }
})