import { describe, expect, it } from 'vitest';
import {
  MAX_MATRIX_CELLS,
  isUnaryOperation,
  matrixToCsv,
  parseMatrixText,
  validateMatrixDimensions,
} from '../utils/matrixHelpers';
import { formatPercent, validateWorkerDisplayName } from '../utils/formatters';

describe('matrixHelpers', () => {
  it('identifies unary vs binary operations accurately', () => {
    expect(isUnaryOperation('T')).toBe(true);
    expect(isUnaryOperation('inv')).toBe(true);
    expect(isUnaryOperation('det')).toBe(true);
    expect(isUnaryOperation('rank')).toBe(true);
    expect(isUnaryOperation('scalar')).toBe(true);
    expect(isUnaryOperation('+')).toBe(false);
    expect(isUnaryOperation('x')).toBe(false);
  });

  it('validates matrix dimensions matching MatrixJobPlan rules', () => {
    expect(validateMatrixDimensions('+', 3, 3, 3, 3)).toBeNull();
    expect(validateMatrixDimensions('+', 3, 3, 3, 4)).not.toBeNull();

    expect(validateMatrixDimensions('x', 2, 3, 3, 4)).toBeNull();
    expect(validateMatrixDimensions('x', 2, 3, 2, 4)).not.toBeNull();

    expect(validateMatrixDimensions('inv', 4, 4)).toBeNull();
    expect(validateMatrixDimensions('inv', 4, 3)).not.toBeNull();
    expect(validateMatrixDimensions('det', 3, 4)).not.toBeNull();

    expect(validateMatrixDimensions('T', 7071, 7071)).toBeNull();
    expect(validateMatrixDimensions('T', 5000, 10000)).toBeNull();
    expect(validateMatrixDimensions('T', 5001, 10000)).not.toBeNull();
    expect(validateMatrixDimensions('x', 8000, 500, 500, 7000)).not.toBeNull();
    expect(50_000_000).toBe(MAX_MATRIX_CELLS);
  });

  it('parses CSV/Excel text and exports CSV cleanly', () => {
    const parsed = parseMatrixText('1, 2, 3\n4\t5\t6');
    expect(parsed.error).toBeNull();
    expect(parsed.matrix).toEqual([
      [1, 2, 3],
      [4, 5, 6],
    ]);
    expect(matrixToCsv(parsed.matrix)).toBe('1,2,3\n4,5,6');

    const bad = parseMatrixText('1, 2\n3, 4, 5');
    expect(bad.matrix).toBeNull();
    expect(bad.error).toContain('Số cột không đồng nhất');
  });
});

describe('formatters', () => {
  it('handles null/invalid metrics safely as UNKNOWN', () => {
    expect(formatPercent(null)).toBe('Không rõ (UNKNOWN)');
    expect(formatPercent(undefined)).toBe('Không rõ (UNKNOWN)');
    expect(formatPercent(-1)).toBe('Không rõ (UNKNOWN)');
    expect(formatPercent(45.67)).toBe('45.7%');
  });

  it('validates worker displayName between 1 and 64 visible characters', () => {
    expect(validateWorkerDisplayName('  Laptop A - Node 1  ')).toEqual({
      valid: true,
      value: 'Laptop A - Node 1',
      error: null,
    });
    expect(validateWorkerDisplayName('   ').valid).toBe(false);
    expect(validateWorkerDisplayName('a'.repeat(65)).valid).toBe(false);
    expect(validateWorkerDisplayName('bad\nname').valid).toBe(false);
  });
});
