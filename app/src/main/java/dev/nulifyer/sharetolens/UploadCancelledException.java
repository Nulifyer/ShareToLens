package dev.nulifyer.sharetolens;

import java.io.IOException;

final class UploadCancelledException extends IOException {
    UploadCancelledException() { super("Upload cancelled"); }
}
