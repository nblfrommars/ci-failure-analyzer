package com.nbl.cfa.classifier;

/**
 * Kết quả phân loại của mô hình. evidenceInLog = true nếu key_evidence thực sự xuất hiện trong log
 * (dùng để phát hiện mô hình bịa bằng chứng).
 */
public record ClassificationResult(ErrorType errorType,
                                   String rootCause,
                                   String keyEvidence,
                                   boolean evidenceInLog) {
}