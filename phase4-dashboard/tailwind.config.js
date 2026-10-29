///** @type {import('tailwindcss').Config} */
//export default {
//  content: ["./index.html", "./src/**/*.{js,jsx}"],
//  theme: {
//    extend: {
//      colors: {
//        ink: {
//          950: '#14132B',
//          900: '#1D1B3B',
//          800: '#252244',
//          700: '#332F5C',
//        },
//        mist: {
//          100: '#EDEBFA',
//          400: '#9D99C2',
//        },
//        mint: { 400: '#7FE7C4', 500: '#5ED9B0' },
//        coral: { 400: '#F2A7A0', 500: '#E8867D' },
//        amber: { 400: '#F4CE8E', 500: '#EAB86A' },
//        lilac: { 400: '#B7A4F5', 500: '#9C82EF' },
//      },
//      fontFamily: {
//        display: ['Sora', 'sans-serif'],
//        body: ['Manrope', 'sans-serif'],
//      },
//    },
//  },
//  plugins: [],
//}


/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,jsx}"],
  theme: {
    extend: {
      colors: {
        panel: { 950: '#1C1917', 900: '#26211C', 800: '#2F2820' },
        hairline: '#3D362C',
        brass: { 400: '#C9A227', 500: '#B08E1F' },
        sage: { 400: '#8FA97A' },
        rust: { 400: '#B5502E' },
        mustard: { 400: '#D4A017' },
        cream: { 100: '#EDE4D3' },
        taupe: { 400: '#A89A85' },
      },
      fontFamily: {
        display: ['Fraunces', 'serif'],
        body: ['Manrope', 'sans-serif'],
      },
    },
  },
  plugins: [],
}