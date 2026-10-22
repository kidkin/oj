import './edu.css'

// These pages sit below HOJ's fixed navigation. Measure its actual height so
// desktop wrapping and the compact navigation never cover page controls.
export default {
  data: () => ({eduHeaderOffset: 80}),
  computed: {eduPageStyle () { return {marginTop: this.eduHeaderOffset + 'px'} }},
  mounted () {
    this.measureEduHeaderAfterRender()
    window.addEventListener('resize', this.measureEduHeaderAfterRender)
  },
  beforeDestroy () { window.removeEventListener('resize', this.measureEduHeaderAfterRender) },
  methods: {
    measureEduHeaderAfterRender () {
      this.$nextTick(() => {
        const header = document.querySelector('#header') || document.querySelector('.mobile-nav')
        const content = document.querySelector('#oj-content')
        const contentTop = content ? content.getBoundingClientRect().top + window.scrollY : 0
        this.eduHeaderOffset = Math.max(0, (header ? header.offsetHeight : 60) + 4 - contentTop)
      })
    }
  }
}
