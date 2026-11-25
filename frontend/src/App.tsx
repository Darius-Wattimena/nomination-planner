import Modal from "react-modal";
import AppRoutes from "./AppRoutes";
import {ToastProvider} from "./ui/Toast";

Modal.setAppElement("#root")

export default function App() {
  return <ToastProvider><AppRoutes/></ToastProvider>
}
