export const MAX_MATRIX_CELLS = 50_000_000;

export const MATRIX_OPERATIONS = [
  { id: '+', label: 'Cộng (+)', unary: false },
  { id: '-', label: 'Trừ (-)', unary: false },
  { id: '%', label: 'Chia lấy dư (%)', unary: false },
  { id: 'x', label: 'Nhân (x)', unary: false },
  { id: 'scalar', label: 'Nhân vô hướng (scalar)', unary: true },
  { id: 'T', label: 'Chuyển vị (T)', unary: true },
  { id: 'inv', label: 'Nghịch đảo (inv)', unary: true },
  { id: 'det', label: 'Định thức (det)', unary: true },
  { id: 'rank', label: 'Hạng (rank)', unary: true },
];

export function isUnaryOperation(op) {
  const normalized = String(op || '').trim().toLowerCase();
  return ['t', 'transpose', 'inv', 'inverse', 'det', 'determinant', 'rank', 'scalar', '*s', 'k*a'].includes(
    normalized
  );
}

export function validateMatrixDimensions(operation, rowsA, colsA, rowsB = 1, colsB = 1, scalar = 2) {
  const op = String(operation || '').trim().toLowerCase();
  const supported = ['+', '-', '%', 'x', '*', 'scalar', 't', 'inv', 'det', 'rank'];
  if (!supported.includes(op)) {
    return `Phép toán không được hỗ trợ: ${operation}`;
  }

  if (!Number.isInteger(rowsA) || !Number.isInteger(colsA) || rowsA <= 0 || colsA <= 0) {
    return 'Kích thước Ma trận A phải là số nguyên dương lớn hơn 0.';
  }
  if (rowsA * colsA > MAX_MATRIX_CELLS) {
    return 'Ma trận A vượt quá giới hạn tối đa 50.000.000 phần tử.';
  }

  if (op === 'scalar' && !Number.isFinite(Number(scalar))) {
    return 'Hệ số vô hướng (scalar) phải là số hữu hạn.';
  }

  if (['inv', 'det'].includes(op) && rowsA !== colsA) {
    return `Phép toán '${operation}' yêu cầu Ma trận A phải là ma trận vuông (số dòng = số cột).`;
  }

  if (!isUnaryOperation(op)) {
    if (!Number.isInteger(rowsB) || !Number.isInteger(colsB) || rowsB <= 0 || colsB <= 0) {
      return 'Kích thước Ma trận B phải là số nguyên dương lớn hơn 0.';
    }
    if (rowsB * colsB > MAX_MATRIX_CELLS) {
      return 'Ma trận B vượt quá giới hạn tối đa 50.000.000 phần tử.';
    }
    if (op === 'x' || op === '*') {
      if (colsA !== rowsB) {
        return `Nhân ma trận (x) yêu cầu số cột của A (${colsA}) phải bằng số dòng của B (${rowsB}).`;
      }
      if (rowsA * colsB > MAX_MATRIX_CELLS) {
        return 'Ma trận kết quả (rowsA × colsB) vượt quá giới hạn tối đa 50.000.000 phần tử.';
      }
    } else if (rowsA !== rowsB || colsA !== colsB) {
      return `Phép toán '${operation}' yêu cầu Ma trận A ([${rowsA}×${colsA}]) và B ([${rowsB}×${colsB}]) có cùng kích thước.`;
    }
  }

  return null;
}

export function createFilledMatrix(rows, cols, fill = 'random') {
  const matrix = [];
  for (let r = 0; r < rows; r++) {
    const row = [];
    for (let c = 0; c < cols; c++) {
      if (fill === 'identity') {
        row.push(r === c ? 1 : 0);
      } else if (fill === 'zero') {
        row.push(0);
      } else {
        row.push(Math.floor(Math.random() * 9) + 1);
      }
    }
    matrix.push(row);
  }
  return matrix;
}

export function resizeMatrix(prev, newRows, newCols) {
  const out = [];
  for (let r = 0; r < newRows; r++) {
    const row = [];
    for (let c = 0; c < newCols; c++) {
      const existing = prev?.[r]?.[c];
      row.push(Number.isFinite(Number(existing)) ? Number(existing) : 0);
    }
    out.push(row);
  }
  return out;
}

export function parseMatrixText(text) {
  if (!text || !text.trim()) {
    return { error: 'Nội dung dữ liệu ma trận đang trống.', matrix: null };
  }
  const lines = text
    .trim()
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);

  if (lines.length === 0) {
    return { error: 'Không tìm thấy dòng dữ liệu nào.', matrix: null };
  }

  const parsed = [];
  let expectedCols = null;

  for (let i = 0; i < lines.length; i++) {
    const tokens = lines[i].split(/[\s,;|\t]+/).filter(Boolean);
    if (tokens.length === 0) {
      return { error: `Dòng ${i + 1} không chứa giá trị hợp lệ.`, matrix: null };
    }
    if (expectedCols === null) {
      expectedCols = tokens.length;
    } else if (tokens.length !== expectedCols) {
      return {
        error: `Số cột không đồng nhất ở dòng ${i + 1} (có ${tokens.length} cột, cần ${expectedCols} cột).`,
        matrix: null,
      };
    }
    const row = [];
    for (let j = 0; j < tokens.length; j++) {
      const val = Number(tokens[j]);
      if (!Number.isFinite(val)) {
        return { error: `Giá trị không phải số hợp lệ tại dòng ${i + 1}, cột ${j + 1}: "${tokens[j]}"`, matrix: null };
      }
      row.push(val);
    }
    parsed.push(row);
  }

  if (parsed.length * expectedCols > MAX_MATRIX_CELLS) {
    return { error: 'Ma trận vượt quá giới hạn 1.000.000 phần tử.', matrix: null };
  }

  return { error: null, matrix: parsed };
}

export function matrixToCsv(matrix) {
  if (!Array.isArray(matrix) || matrix.length === 0) return '';
  return matrix.map((row) => row.join(',')).join('\n');
}

export function downloadTextFile(filename, content, mimeType = 'text/csv;charset=utf-8;') {
  const blob = new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}
