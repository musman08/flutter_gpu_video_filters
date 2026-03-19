import 'dart:io';

import 'input_source.dart';

class VideoExportConfig {
  final PathInputSource source;
  final File output;
  final VideoExportFormat format;
  final int? height;

  VideoExportConfig(
    this.source,
    this.output, {
    this.format = VideoExportFormat.auto,
    this.height,
  });
}

enum VideoExportFormat { mp4, mov, auto }

extension VideoExportFormatX on VideoExportFormat {
  String get platformKey {
    switch (this) {
      case VideoExportFormat.mp4:
        return 'mp4';
      case VideoExportFormat.mov:
        return 'mov';
      case VideoExportFormat.auto:
        return 'auto';
    }
  }
}
