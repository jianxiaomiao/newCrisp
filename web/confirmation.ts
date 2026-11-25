import { ElMessageBox } from 'element-plus';

export async function confirmAction(title: string, message: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, {
      confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning',
      closeOnClickModal: false, autofocus: false,
    });
    return true;
  } catch {
    return false;
  }
}
