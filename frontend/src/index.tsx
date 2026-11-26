import {createRoot} from 'react-dom/client'
import {BrowserRouter} from "react-router";
import App from './App';
import {applyTheme} from "./utils/theme";
import './styles/app.css'

applyTheme()

createRoot(document.getElementById('root')!).render(
  <BrowserRouter>
    <App/>
  </BrowserRouter>
);
