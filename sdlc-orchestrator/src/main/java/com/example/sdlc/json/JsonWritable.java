package com.example.sdlc.json;

/** Implemented by model classes that serialise themselves to JSON-compatible maps. */
public interface JsonWritable {
    Object toJson();
}
