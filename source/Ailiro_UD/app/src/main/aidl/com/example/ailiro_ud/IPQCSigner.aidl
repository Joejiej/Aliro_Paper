// IPQCSigner.aidl
package com.example.ailiro_ud;

// Declare any non-default types here with import statements

interface IPQCSigner {
    /**
     * Demonstrates some basic types that you can use as parameters
     * and return values in AIDL.
     */
    byte[] sign(in byte[] rawPrivKey, in byte[] message);
}